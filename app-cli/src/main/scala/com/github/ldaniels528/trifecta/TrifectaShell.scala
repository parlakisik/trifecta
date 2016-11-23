package com.github.ldaniels528.trifecta

import java.io.PrintStream

import com.datastax.driver.core.{CodecRegistry, ColumnDefinitions, ResultSet, Row}
import com.github.ldaniels528.trifecta.command.parser.CommandParser
import com.github.ldaniels528.trifecta.io.AsyncIO
import com.github.ldaniels528.trifecta.messages.query.parser.KafkaQueryParser
import com.github.ldaniels528.trifecta.modules.ModuleHelper.die
import com.github.ldaniels528.trifecta.modules.azure.AzureModule
import com.github.ldaniels528.trifecta.modules.cassandra.CassandraModule
import com.github.ldaniels528.trifecta.modules.core.CoreModule
import com.github.ldaniels528.trifecta.modules.elasticsearch.ElasticSearchModule
import com.github.ldaniels528.trifecta.modules.etl.ETLModule
import com.github.ldaniels528.trifecta.modules.kafka.KafkaSandbox
import com.github.ldaniels528.trifecta.modules.mongodb.MongoModule
import org.apache.zookeeper.KeeperException.ConnectionLossException
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.tools.jline.console.ConsoleReader
import scala.tools.jline.console.history.FileHistory
import scala.util.{Failure, Success, Try}

/**
  * Trifecta Shell (REPL)
  * @author lawrence.daniels@gmail.com
  */
object TrifectaShell {
  private val logger = LoggerFactory.getLogger(getClass)
  val VERSION = CoreModule.VERSION

  /**
    * Application entry point
    * @param args the given command line arguments
    */
  def main(args: Array[String]) {
    // use the ANSI console plugin to display the title line
    System.out.println(s"Trifecta v$VERSION")

    // load the configuration
    logger.info(s"Loading configuration file '${TxConfig.configFile}'...")
    val config = Try(TxConfig.load(TxConfig.configFile)) match {
      case Success(cfg) => cfg
      case Failure(e) =>
        val cfg = TxConfig.defaultConfig
        if (!TxConfig.configFile.exists()) {
          logger.info(s"Creating default configuration file (${TxConfig.configFile.getAbsolutePath})...")
          cfg.save(TxConfig.configFile)
        }
        cfg
    }

    // startup the Kafka Sandbox?
    if (args.contains("--kafka-sandbox")) {
      logger.info("Starting Kafka Sandbox...")
      val kafkaSandbox = KafkaSandbox()
      config.zooKeeperConnect = kafkaSandbox.getConnectString
      Thread.sleep(3000)
    }

    // initialize the shell
    val console = new TrifectaConsole(new TxRuntimeContext(config))

    // if arguments were not passed, stop.
    args.filterNot(_.startsWith("--")).toList match {
      case Nil =>
        console.shell()
      case params =>
        val line = params mkString " "
        console.execute(line)
    }
  }

  /**
    * Trifecta Console Shell Application
    * @author lawrence.daniels@gmail.com
    */
  class TrifectaConsole(rt: TxRuntimeContext) {
    private val config = rt.config

    // redirect standard output
    val out: PrintStream = config.out
    val err: PrintStream = config.err

    // define the job manager
    private val jobManager = new JobManager()

    // create the result handler
    private val resultHandler = new TxResultHandler(config, jobManager)

    // added CLI-specific modules
    rt.moduleManager ++= Seq(
      new CoreModule(config, jobManager),
      new AzureModule(config),
      new CassandraModule(config),
      new ElasticSearchModule(config),
      new ETLModule(config),
      new MongoModule(config))

    /**
      * Executes the given command line expression
      * @param line the given command line expression
      */
    def execute(line: String) {
      interpret(line) match {
        case Success(result) =>
          // if debug is enabled, display the object value and class name
          if (config.debugOn) showDebug(result)

          // handle the result
          handleResult(result, line)
        case Failure(e: ConnectionLossException) =>
          err.println("Zookeeper connect loss error - use 'zconnect' to re-establish a connection")
        case Failure(e: IllegalArgumentException) =>
          if (rt.config.debugOn) e.printStackTrace()
          err.println(s"Syntax error: ${getErrorMessage(e)}")
        case Failure(e) =>
          if (rt.config.debugOn) e.printStackTrace()
          err.println(s"Runtime error: ${getErrorMessage(e)}")
      }
    }

    /**
      * Interactive shell
      */
    def shell() {
      // use the ANSI console plugin to display the title line
      out.println("Type 'help' (or ?) to see the list of available commands")

      if (rt.config.autoSwitching) {
        out.println("Module Auto-Switching is On")
      }

      // define the console reader
      val consoleReader = new ConsoleReader()
      consoleReader.setExpandEvents(false)
      val history = new FileHistory(TxConfig.historyFile)
      consoleReader.setHistory(history)

      do {
        // display the prompt, and get the next line of input
        val module = rt.moduleManager.activeModule getOrElse rt.moduleManager.modules.head

        // read a line from the console
        Try {
          Option(consoleReader.readLine("%s:%s> ".format(module.moduleLabel, module.prompt))) map (_.trim) foreach { line =>
            if (line.nonEmpty) {
              interpret(line) match {
                case Success(result) =>
                  // if debug is enabled, display the object value and class name
                  if (config.debugOn) showDebug(result)

                  // handle the result
                  handleResult(result, line)
                case Failure(e: ConnectionLossException) =>
                  err.println("Zookeeper connect loss error - use 'zconnect' to re-establish a connection")
                case Failure(e: IllegalArgumentException) =>
                  if (rt.config.debugOn) e.printStackTrace()
                  err.println(s"Syntax error: ${getErrorMessage(e)}")
                case Failure(e) =>
                  if (rt.config.debugOn) e.printStackTrace()
                  err.println(s"Runtime error: ${getErrorMessage(e)}")
              }
            }
          }
        }
      } while (config.isAlive)

      // flush the console
      consoleReader.flush()
      consoleReader.setExpandEvents(false)

      // shutdown all resources
      rt.shutdown()
    }

    /**
      * Executes a local system command
      * @example `ps -ef`
      */
    private def executeCommand(command: String): Try[String] = {
      import scala.sys.process._

      Try(command.!!)
    }

    private def handleResult(result: Any, input: String)(implicit ec: ExecutionContext) = {
      result match {
        case r: ResultSet => handleCassandraResultSet(r)
        case x => resultHandler.handleResult(x, input)
      }
    }

    private def interpret(input: String): Try[Any] = {
      input.trim match {
        case s if s.startsWith("`") && s.endsWith("`") => executeCommand(s.drop(1).dropRight(1))
        case s => interpretCommandLine(s)
      }
    }

    /**
      * Interprets command line input
      * @param input the given line of input
      * @return a try-monad wrapped result
      */
    private def interpretCommandLine(input: String) = Try {
      // is the input a query?
      if (input.startsWith("select")) KafkaQueryParser(input).executeQuery(rt)
      else {
        // parse the input into tokens
        val tokens = CommandParser.parseTokens(input)

        // convert the tokens into Unix-style arguments
        val unixArgs = CommandParser.parseUnixLikeArgs(tokens)

        // match the command
        val commandSet = rt.moduleManager.commandSet

        for {
          commandName <- unixArgs.commandName
          command = commandSet.getOrElse(commandName, die(s"command '$commandName' not found"))

        } yield {
          // verify and execute the command
          command.params.checkArgs(command, tokens)
          val result = command.fx(unixArgs)

          // auto-switch modules?
          if (config.autoSwitching && (command.promptAware || command.module.moduleName != "core")) {
            rt.moduleManager.setActiveModule(command.module)
          }
          result
        }
      }
    }

    private def showDebug(result: Any): Unit = {
      result match {
        case Failure(e) => e.printStackTrace(out)
        case AsyncIO(task, counter) => showDebug(task.value)
        case f: Future[_] => showDebug(f.value)
        case Some(v) => showDebug(v)
        case Success(v) => showDebug(v)
        case v => out.println(s"result: $result ${Option(result) map (_.getClass.getName) getOrElse ""}")
      }
    }

    private def getErrorMessage(t: Throwable): String = {
      Option(t.getMessage) getOrElse t.toString
    }

    /**
      * Handles a Cassandra Result Set
      * @param rs the given [[ResultSet]]
      */
    private def handleCassandraResultSet(rs: ResultSet): Unit = {
      val cds = rs.getColumnDefinitions.asList().toSeq
      val records = rs.all() map (decodeRow(_, cds))
      resultHandler.tabular.transformMaps(records) foreach out.println
    }

    private def decodeRow(row: Row, cds: Seq[ColumnDefinitions.Definition]): Map[String, Any] = {
      Map(cds map { cd =>
        val name = cd.getName
        val javaType = CodecRegistry.DEFAULT_INSTANCE.codecFor(cd.getType).getJavaType.getRawType
        val value = javaType match {
          case c if c == classOf[Array[Byte]] => row.getBytes(name)
          case c if c == classOf[java.math.BigDecimal] => row.getDecimal(name)
          case c if c == classOf[java.math.BigInteger] => row.getVarint(name)
          case c if c == classOf[java.lang.Boolean] => row.getBool(name)
          case c if c == classOf[java.util.Date] => row.getDate(name)
          case c if c == classOf[java.lang.Double] => row.getDouble(name)
          case c if c == classOf[java.lang.Float] => row.getFloat(name)
          case c if c == classOf[java.lang.Integer] => row.getInt(name)
          case c if c == classOf[java.lang.Long] => row.getLong(name)
          case c if c == classOf[java.util.Map[_, _]] => row.getMap(name, classOf[String], classOf[Object])
          case c if c == classOf[java.util.Set[_]] => row.getSet(name, classOf[Object])
          case c if c == classOf[String] => row.getString(name)
          case c if c == classOf[java.util.UUID] => row.getUUID(name)
          case c =>
            throw new IllegalStateException(s"Unsupported class type ${javaType.getName} for column ${cd.getTable}.$name")
        }
        (name, value)
      }: _*)
    }

  }

}