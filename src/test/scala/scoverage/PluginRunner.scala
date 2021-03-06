package scoverage

import java.io.{FileNotFoundException, File}
import scala.tools.nsc.transform.{TypingTransformers, Transform}
import java.net.URL
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.Global
import scala.collection.mutable.ListBuffer

/** @author Stephen Samuel */
trait PluginSupport {

  val scalaVersion = "2.10.3"
  val shortScalaVersion = "2.10"

  val settings = new scala.tools.nsc.Settings
  val classPath = getScalaJars.map(_.getAbsolutePath) :+ sbtCompileDir.getAbsolutePath
  settings.Xprint.value = List("all")
  settings.Yrangepos.value = true
  settings.classpath.value = classPath.mkString(":")

  val reporter = new scala.tools.nsc.reporters.ConsoleReporter(settings)

  val compiler = new ScoverageAwareCompiler(settings, reporter)

  def writeCodeSnippetToTempFile(code: String): File = {
    val file = File.createTempFile("scoverage_snippet", ".scala")
    org.apache.commons.io.FileUtils.write(file, code)
    file
  }

  def addToClassPath(groupId: String, artifactId: String, version: String): Unit = {
    settings.classpath.value = settings.classpath.value + ":" + findIvyJar(groupId, artifactId, version).getAbsolutePath
  }

  def compileCodeSnippet(code: String): ScoverageAwareCompiler = compileSourceFiles(writeCodeSnippetToTempFile(code))
  def compileSourceResources(urls: URL*): ScoverageAwareCompiler = {
    compileSourceFiles(urls.map(_.getFile).map(new File(_)): _*)
  }
  def compileSourceFiles(files: File*): ScoverageAwareCompiler = {
    val command = new scala.tools.nsc.CompilerCommand(files.map(_.getAbsolutePath).toList, settings)
    new compiler.Run().compile(command.files)
    compiler
  }

  def getScalaJars: List[File] = {
    val scalaJars = List("scala-compiler.jar", "scala-library.jar", "scala-reflect.jar")
    scalaJars.map(findScalaJar)
  }

  def findScalaJar(jarName: String): File = {
    val userHome = System.getProperty("user.home")
    val sbtHome = userHome + "/.sbt"
    val sbtScalaLibs = sbtHome + "/boot/scala-" + scalaVersion + "/lib"
    val file = new File(sbtScalaLibs + "/" + jarName)
    if (file.exists) file else throw new FileNotFoundException(s"Could not locate [$jarName]. Tests require SBT 0.13+")
  }

  def findIvyJar(groupId: String, artifactId: String, version: String): File = {
    val userHome = System.getProperty("user.home")
    val sbtHome = userHome + "/.ivy2"
    val jarPath = sbtHome + "/cache/" + groupId + "/" + artifactId + "/jars/" + artifactId + "-" + version + ".jar"
    val file = new File(jarPath)
    if (file.exists) file else throw new FileNotFoundException(s"Could not locate [$jarPath]. Tests require SBT 0.13+")
  }

  def sbtCompileDir: File = {
    val dir = new File("./target/scala-" + shortScalaVersion + "/classes")
    if (dir.exists) dir
    else throw new FileNotFoundException(s"Could not locate SBT compile directory for plugin files [$dir]")
  }

  def assertNMeasuredStatements(n: Int): Unit = {
    for ( k <- 1 to n ) {
      assert(compiler.testStore.sources.mkString(" ").contains(s"scoverage.Invoker.invoked($k,"))
    }
  }
}

class ScoverageAwareCompiler(settings: scala.tools.nsc.Settings, reporter: scala.tools.nsc.reporters.Reporter)
  extends scala.tools.nsc.Global(settings, reporter) {

  val preComponent = new ScoveragePreComponent(this)
  val instrumentationComponent = new ScoverageInstrumentationComponent(this)
  val testStore = new ScoverageTestStoreComponent(this)
  instrumentationComponent.setOptions(new ScoverageOptions())

  class ScoverageTestStoreComponent(val global: Global) extends PluginComponent with TypingTransformers with Transform {

    val sources = new ListBuffer[String]

    override val phaseName: String = "scoverage-teststore"
    override val runsAfter: List[String] = List("dce")
    override val runsBefore = List[String]("terminal")

    override protected def newTransformer(unit: global.CompilationUnit): global.Transformer = new Transformer(unit)
    class Transformer(unit: global.CompilationUnit) extends TypingTransformer(unit) {

      override def transform(tree: global.Tree) = {
        sources append tree.toString
        tree
      }
    }
  }

  override def computeInternalPhases() {
    val phs = List(
      syntaxAnalyzer -> "parse source into ASTs, perform simple desugaring",
      preComponent -> "scoverage preComponent",
      analyzer.namerFactory -> "resolve names, attach symbols to named trees",
      analyzer.packageObjects -> "load package objects",
      analyzer.typerFactory -> "the meat and potatoes: type the trees",
      instrumentationComponent -> "scoverage instrumentationComponent",
      patmat -> "translate match expressions",
      superAccessors -> "add super accessors in traits and nested classes",
      extensionMethods -> "add extension methods for inline classes",
      pickler -> "serialize symbol tables",
      refChecks -> "reference/override checking, translate nested objects",
      uncurry -> "uncurry, translate function values to anonymous classes",
      tailCalls -> "replace tail calls by jumps",
      specializeTypes -> "@specialized-driven class and method specialization",
      explicitOuter -> "this refs to outer pointers, translate patterns",
      erasure -> "erase types, add interfaces for traits",
      postErasure -> "clean up erased inline classes",
      lazyVals -> "allocate bitmaps, translate lazy vals into lazified defs",
      lambdaLift -> "move nested functions to top level",
      constructors -> "move field definitions into constructors",
      mixer -> "mixin composition",
      cleanup -> "platform-specific cleanups, generate reflective calls",
      genicode -> "generate portable intermediate code",
      inliner -> "optimization: do inlining",
      inlineExceptionHandlers -> "optimization: inline exception handlers",
      closureElimination -> "optimization: eliminate uncalled closures",
      deadCode -> "optimization: eliminate dead code",
      testStore -> "scoverage teststore",
      terminal -> "The last phase in the compiler chain"
    )
    phs foreach (addToPhasesSet _).tupled
  }
}


