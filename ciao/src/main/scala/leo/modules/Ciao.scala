package leo.modules

import leo.datastructures.TPTP.Problem
import leo.modules.input.TPTPParser
import leo.modules.ciao.ProblemAugment

import scala.io.Source
import java.io.{File, FileNotFoundException, PrintWriter}

object Ciao {
  final val name: String = "ciao"
  final val version: String = "1.0"

  private[this] var inputFileName = ""
  private[this] var outputFileNameTemplate: Option[String] = None
  private[this] var tstpOutput: Boolean = false
  private[this] var collectOutput: Boolean = false

  final def main(args: Array[String]): Unit = {
    if (args.contains("--help")) {
      usage(); return
    }
    if (args.contains("--version")) {
      printVersion(); return
    }
    if (args.isEmpty) usage()
    else {
      var infile: Option[Source] = None
      var outfile: Option[PrintWriter] = None
      var error: Option[String] = None
      try {
        parseArgs(args.toSeq)
        // Read input
        infile = Some(if (inputFileName == "-") io.Source.stdin else io.Source.fromFile(inputFileName))
        // Parse input
        val parsedInput = TPTPParser.problem(infile.get)
        val augmented = ProblemAugment(parsedInput)
        if (collectOutput) {
          if (augmented.nonEmpty) {
            //val allAugmented = augmented.flatMap { fs => Seq(fs(0), fs(2)) }.toSeq ++ augmented.flatMap { fs => Seq(fs(1), fs(3)) }.toSeq
            //println(s"names: ${augmented.map(aug => s"${aug.map(_.name).mkString(", ")}").mkString(s" and ")}")
            val allAugmented = augmented.toSeq.flatten
            val augmentedProblem = Problem(parsedInput.includes, parsedInput.formulas :++ allAugmented, parsedInput.formulaComments)
            val result = generateResult(tptpProblemToString(augmentedProblem), "Success", "ListOfTHF")
            outfile = Some(if (outputFileNameTemplate.isEmpty) new PrintWriter(System.out) else new PrintWriter(new File(s"${outputFileNameTemplate.get}")))
            outfile.get.print(result)
            outfile.get.flush()
            if (outputFileNameTemplate.nonEmpty) outfile.foreach(_.close())
          }
        } else {
          var outputNumber = 1
          augmented.foreach { augment =>
            val augmentedProblem = Problem(parsedInput.includes, parsedInput.formulas :++ augment, parsedInput.formulaComments)
            val result = generateResult(tptpProblemToString(augmentedProblem), "Success", "ListOfTHF")
            // Allocate output file
            outfile = Some(if (outputFileNameTemplate.isEmpty) new PrintWriter(System.out) else new PrintWriter(new File(s"${outputFileNameTemplate.get}.$outputNumber")))
            outfile.get.print(result)
            outfile.get.flush()
            if (outputFileNameTemplate.nonEmpty) outfile.foreach(_.close())
            outputNumber += 1
          }
        }
      } catch {
        case e: IllegalArgumentException =>
          error = Some(e.getMessage)
          if (!tstpOutput) usage()
        case e: FileNotFoundException =>
          error = Some(s"File cannot be found or is not readable/writable: ${e.getMessage}")
        case e: TPTPParser.TPTPParseException =>
          error = Some(s"Input file could not be parsed, parse error at ${e.line}:${e.offset}: ${e.getMessage}")
        case e: Throwable =>
          error = Some(s"Unexpected error: ${e.getMessage} (${e.printStackTrace()}). This is considered an implementation error; please report this!")
      } finally {
        if (error.nonEmpty) {
          if (tstpOutput) {
            if (outfile.isDefined) {
              outfile.get.println(generateResult("", "Error", "", error.get))
              outfile.get.flush()
            } else println(generateResult("", "Error", "", error.get))
          } else {
            if (outfile.isDefined) {
              outfile.get.println(s"Error: ${error.get}")
              outfile.get.flush()
              if (outputFileNameTemplate.isDefined) {
                if (outputFileNameTemplate.get != "-") System.err.println(s"Error: ${error.get}")
              }
            } else println(s"Error: ${error.get}")
          }
        }
        try {
          infile.foreach(_.close())
          outfile.foreach(_.close())
        } catch {
          case _: Throwable => ()
        }
        if (error.nonEmpty) System.exit(1)
      }
    }
  }

  private final def tptpProblemToString(problem: Problem): String = {
    val sb: StringBuilder = new StringBuilder()
    problem.includes foreach { case (file, (selection, _)) =>
      if (selection.isEmpty) sb.append(s"include('$file').\n")
      else sb.append(s"include('$file', ${selection.mkString("[",",","]")}).\n")
    }
    problem.formulas foreach { f =>
      sb.append(s"${f.pretty}\n")
    }
    if (problem.includes.nonEmpty || problem.formulas.nonEmpty) sb.dropRight(1).toString()
    else sb.toString()
  }

  private[this] final def generateResult(result: String, szsStatus: String, szsDatatype: String, additionalInfo: String = ""): String = {
    import java.util.Calendar

    val sb: StringBuilder = new StringBuilder()
    if (tstpOutput) sb.append(s"% SZS status $szsStatus${if (inputFileName.nonEmpty) s" for $inputFileName" else ""}${if (additionalInfo.nonEmpty) s": $additionalInfo" else ""}\n")
    if (result.nonEmpty) {
      sb.append(s"%%% This output was generated by $name, version $version.\n")
      sb.append(s"%%% Generated on ${Calendar.getInstance().getTime.toString}.\n")
      if (tstpOutput) sb.append(s"% SZS output start $szsDatatype${if (inputFileName.nonEmpty) s" for $inputFileName"}\n")
      if (result.nonEmpty) sb.append(result)
      sb.append("\n")
      if (tstpOutput) sb.append(s"% SZS output end $szsDatatype${if (inputFileName.nonEmpty) s" for $inputFileName"}\n")
    }
    sb.toString()
  }

  private[this] final def printVersion(): Unit = {
    println(s"$name $version")
  }

  private[this] final def usage(): Unit = {
    println(s"usage: $name [options] <problem file>")
    println(
      """
        | <command> is the command to be executed (see below). <problem file> can be
        | either a file name or '-' (without quotes) for stdin. If <output file> is
        | specified, the result is written to <output file>, otherwise to stdout.
        |
        | Options:
        |  --tstp                   Enable TSTP-compatible output: The output in <output file>
        |                           (or stdout) will start with a SZS status value and the output
        |                           will be wrapped within SZS BEGIN and SZS END block delimiters.
        |                           Disabled by default.
        |  --output <output file>   Write the result to a file instead of stdout
        |  --collect                Collect all augmentations in one file.
        |  --version                Prints the version number of the executable and terminates.
        |  --help                   Prints this description and terminates.
        |""".stripMargin)
  }

  private[this] final def parseArgs(args: Seq[String]): Unit = {
    var args0 = args
    var readInputFile = false
    try {
      while (args0.nonEmpty && !readInputFile) {
        val hd = args0.head
        hd match {
          case "--tstp" =>
            tstpOutput = true
          case "--output" =>
            outputFileNameTemplate = Some(args0.tail.head)
            args0 = args0.tail
          case "--collect" =>
            collectOutput = true
          case _ => // input file, and then done
            inputFileName = hd
            readInputFile = true
        }
        args0 = args0.tail
      }
      if (!readInputFile) throw new IllegalArgumentException(s"Input file needs to be specified.")
      if (args0.nonEmpty) throw new IllegalArgumentException(s"Superfluous arguments: ${args0.mkString(",")}")
    } catch {
      case _:NoSuchElementException | _:ArrayIndexOutOfBoundsException =>
        throw new IllegalArgumentException(s"Not enough or illegal arguments supplied.")
    }
  }
}

