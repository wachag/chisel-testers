// See LICENSE for license details.
package Chisel.iotesters

import java.io.File

import Chisel._

import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Future, _}
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Random
import java.nio.channels.FileChannel

private[iotesters] object setupVerilatorBackend {
  def apply(dutGen: ()=> Chisel.Module): Backend = {
    val rootDirPath = new File(".").getCanonicalPath()
    val testDirPath = s"${rootDirPath}/test_run_dir"
    val dir = new File(testDirPath)
    dir.mkdirs()

    val circuit = Chisel.Driver.elaborate(dutGen)
    // Dump FIRRTL for debugging
    val firrtlIRFilePath = s"${testDirPath}/${circuit.name}.ir"
    Chisel.Driver.dumpFirrtl(circuit, Some(new File(firrtlIRFilePath)))
    // Parse FIRRTL
    //val ir = firrtl.Parser.parse(Chisel.Driver.emit(dutGen) split "\n")
    // Generate Verilog
    val verilogFilePath = s"${testDirPath}/${circuit.name}.v"
    //val v = new PrintWriter(new File(s"${dir}/${circuit.name}.v"))
    firrtl.Driver.compile(firrtlIRFilePath, verilogFilePath, new firrtl.VerilogCompiler())

    val verilogFileName = verilogFilePath.split("/").last
    val cppHarnessFileName = "classic_tester_top.cpp"
    val cppHarnessFilePath = s"${testDirPath}/${cppHarnessFileName}"
    val cppBinaryPath = s"${testDirPath}/V${circuit.name}"
    val vcdFilePath = s"${testDirPath}/${circuit.name}.vcd"

    copyVerilatorHeaderFiles(testDirPath)

    genVerilatorCppHarness(dutGen, verilogFileName, cppHarnessFilePath, vcdFilePath)
    Chisel.Driver.verilogToCpp(verilogFileName.split("\\.")(0), new File(testDirPath), Seq(), new File(cppHarnessFilePath)).!
    Chisel.Driver.cppToExe(verilogFileName.split("\\.")(0), new File(testDirPath)).!

    lazy val dut = dutGen() //HACK to get Module instance for now; DO NOT copy
    Driver.elaborate(() => dut)

    new VerilatorBackend(dut, cppBinaryPath)
  }
}

private[iotesters] class VerilatorBackend(dut: Module, cmd: String = chiselMain.context.testCmd mkString " ", verbose: Boolean = true, _seed: Long = System.currentTimeMillis) extends Backend {
  val simApiInterface = new SimApiInterface(dut, cmd)
  val rnd = new Random(_seed)

  private val ioNameMap = {
    val result = HashMap[Data, String]()
    def getIPCName(arg: (Bits, (String, String))) = arg match {case (io, (_, name)) =>
      result(io) = name
    }
    val (inputMap, outputMap) = getPortNameMaps(dut)
    (inputMap map getIPCName, outputMap map getIPCName)
    result
  }

  private def getIPCName(data: Data) = ioNameMap getOrElse (data, "<no signal name>")

  override def poke(signal: Bits, value: BigInt) {
    val name = getIPCName(signal)
    if (verbose) println(s"  POKE ${name} <- ${bigIntToStr(value, 16)}")
    simApiInterface.poke(name, value)
  }

  override def peek(signal: Bits) = {
    val name = getIPCName(signal)
    val result = simApiInterface.peek(name) getOrElse BigInt(rnd.nextInt)
    if (verbose) println(s"  PEEK ${name} -> ${bigIntToStr(result, 16)}")
    result
  }

  override def expect(signal: Bits, expected: BigInt, msg: => String = "") = {
    val name = getIPCName(signal)
    val got = simApiInterface.peek(name) getOrElse BigInt(rnd.nextInt)
    val good = got == expected
    if (verbose) println(s"""${msg}  EXPECT ${name} -> ${bigIntToStr(got, 16)} == ${bigIntToStr(expected, 16)} ${if (good) "PASS" else "FAIL"}""")
    good
  }

  override def step(n: Int): Unit = {
    simApiInterface.step(n)
  }

  override def reset(n: Int = 1): Unit = {
    simApiInterface.reset(n)
  }

  override def finish: Unit = {
    simApiInterface.finish
  }
}

