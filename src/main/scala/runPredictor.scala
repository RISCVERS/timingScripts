package scalaTage

import scala.collection.mutable
import scala.util.matching.Regex
// import scala.collection.immutable._
import scala.math._
import scala.util._
import sys.process._
import java.io.File

object utils {
    def getLogs(path: String): Array[String] = {
        val files = s"ls $path".!!.split('\n')
        val logPattern = raw"log".r.unanchored
        files.map(f => if ((logPattern findAllIn f).length != 0) path+f else "").filter(_ != "").toArray
    }
}


class BranchPredictorRunner() {
    val tw = new TraceWrapper
    val bp = new Tage

    val maxBrPrint = 10

    def getCfis(file: String): Iterator[CFIInfo] = tw.getCFIInfosFromFile(file)

    def runWithCFIInfo(cfis: Iterator[CFIInfo]) = {     
        //                               pc  , (mis, cor)
        val stats = new mutable.HashMap [Long, Array[Int]] 
        cfis.foreach { case CFIInfo(isBr, pc, taken, misPred) => 
            // we only care about branches
            if (isBr) {
                val pred = bp.predict(pc)
                bp.update(pc, taken, pred)
                if (taken != pred) {
                    if (stats.contains(pc)) stats(pc)(0) += 1
                    else stats += (pc -> Array(1, 0))
                }
                else {
                    if (stats.contains(pc)) stats(pc)(1) += 1
                    else stats += (pc -> Array(0, 1))
                }
            } else {
                bp.updateUncond
            }
        }

        def getAndPrintPreds(stats: mutable.HashMap [Long, Array[Int]]): (Int, Int, Int) = {
            var brPrint = 0
            var totalPred = 0
            var totalMiss = 0
            var totalCorr = 0
            stats.toList.sortBy(_._2(0)).reverse.foreach{ case(pc, arr) => {
                val miss = arr(0)
                val corr = arr(1)
                if (brPrint < maxBrPrint) { println(f"pc: $pc%x, mispredict: ${miss}%10d, correct: ${corr}%10d, total: ${miss+corr}%10d, missrate: ${miss*100/(miss+corr).toDouble}%3.2f%%"); brPrint += 1; }
                totalMiss += arr(0)
                totalCorr += arr(1)
                totalPred += arr.reduce(_+_)
            }}
            (totalMiss, totalCorr, totalPred)
        }
        println(f"Printing top $maxBrPrint%d mispredicted branches:")

        val (totalMispred, totalCorrect, totalBrs) = getAndPrintPreds(stats)

        println(f"Totally mispredicted $totalMispred%d out of $totalBrs%d branches")

        // after one program is done, flush predictor for the next program
        bp.flush

        (totalCorrect, totalMispred)
    }

    def runWithLogs(logs: Array[String]): Array[(String, (Int, Int))] = {
        println(f"Running with ${bp}%s")
        logs.map(l => {
            val log = new File(l)
            if (log.exists()) {
                println(s"processing log $l")
                (l, runWithCFIInfo(getCfis(l)))
            }
            else {
                println(s"$l not exists")
                (l, (1, 1))
            }
        }).toArray
    }

    def printRes(res: Array[(String, (Int, Int))]) = {
        res.foreach { case(l, (c, m)) => {
            println(f"test: ${l.split('/').last}%20s, $m%6d/${c+m}%8d mispredicted, missrate: ${(m*100).toDouble/(c+m)}%3.3f%%")
        }}
    }

}

object BranchPredictorRunnerTest {
    val usage = """
        Usage: --log-in-debug logname
    """
    def main(args: Array[String]): Unit = {
        if (args.length == 0) println(usage)
        val arglist = args.toList
        type OptionMap = Map[Symbol, Any]

        def nextOption(map : OptionMap, list: List[String]) : OptionMap = {
            def isSwitch(s : String)= (s(0) == '-')
            def fileToPathInDebug(file: String) = "/home/glr/XiangShan/debug/" + file + ".log"
            list match {
                case Nil => map
                case "--log-in-debug" :: file :: tail => 
                    nextOption(map ++ Map('file -> fileToPathInDebug(file)), tail)
                // case string :: opt2 :: tail if isSwitch(opt2) => 
                //                     nextOption(map ++ Map('infile -> string), list.tail)
                // case string :: Nil =>  nextOption(map ++ Map('infile -> string), list.tail)
                case option :: tail => { println("Unknown option "+option); nextOption(map, list.tail);}
            }
        }
        val options = nextOption(Map(),arglist)
        val bpr = new BranchPredictorRunner
        // val logs = utils.getLogs("/home/glr/nexus-am/tests/cputest/build/")
        // val logs = Array("/home/glr/XiangShan/debug/dhrystone.log")
        val logs = Array(options('file).toString)
        // val logs = Array("/home/glr/XiangShan/debug/coremark10.log")
        // val logs = Array("/home/glr/XiangShan/debug/microbench.log")
        // val logs = (0 until 10).map(_ => "/home/glr/XiangShan/debug/coremark.log").toArray
        val res = bpr.runWithLogs(logs)
        bpr.printRes(res)
    }
}