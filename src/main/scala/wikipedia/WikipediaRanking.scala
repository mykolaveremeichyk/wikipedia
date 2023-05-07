package wikipedia

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext.*
import org.apache.log4j.{Logger, Level}

import org.apache.spark.rdd.RDD
import scala.util.Properties.isWin

case class WikipediaArticle(title: String, text: String):
  /**
    * @return Whether the text of this article mentions `lang` or not
    * @param lang Language to look for (e.g. "Scala")
    */
  def mentionsLanguage(lang: String): Boolean = text.split(' ').contains(lang)

object WikipediaRanking extends WikipediaRankingInterface:
  // Reduce Spark logging verbosity
  Logger.getLogger("org.apache.spark").setLevel(Level.ERROR)
//  if isWin then System.setProperty("hadoop.home.dir", System.getProperty("user.dir") + "\\winutils\\hadoop-3.3.1")

  val langs = List(
    "JavaScript", "Java", "PHP", "Python", "C#", "C++", "Ruby", "CSS",
    "Objective-C", "Perl", "Scala", "Haskell", "MATLAB", "Clojure", "Groovy")

  val conf: SparkConf = new SparkConf().setAppName("Programming Language Ranking").setMaster("local")
  val sc: SparkContext = new SparkContext(conf)

  // Hint: use a combination of `sc.parallelize`, `WikipediaData.lines` and `WikipediaData.parse`
  private val lines: List[String] = WikipediaData.lines
  private val listOfArticles: List[WikipediaArticle] = lines.map(line => WikipediaData.parse(line))

  val wikiRdd: RDD[WikipediaArticle] =  sc.parallelize(listOfArticles)

  /** Returns the number of articles on which the language `lang` occurs.
   *  Hint1: consider using method `aggregate` on RDD[T].
   *  Hint2: consider using method `mentionsLanguage` on `WikipediaArticle`
   */


  def occurrencesOfLang(lang: String, rdd: RDD[WikipediaArticle]): Int = rdd.aggregate(0)
    (
      (acc: Int, article: WikipediaArticle) => if (article.mentionsLanguage(lang)) acc + 1 else acc,
      (acc1: Int, acc2: Int) => acc1 + acc2
    )

  /* (1) Use `occurrencesOfLang` to compute the ranking of the languages
   *     (`val langs`) by determining the number of Wikipedia articles that
   *     mention each language at least once. Don't forget to sort the
   *     languages by their occurrence, in decreasing order!
   *
   *   Note: this operation is long-running. It can potentially run for
   *   several seconds.
   */
  def rankLangs(langs: List[String], rdd: RDD[WikipediaArticle]): List[(String, Int)] =
    val langAndRank: List[(String, Int)] = langs.map(lang => (lang, occurrencesOfLang(lang, rdd)))
    langAndRank.sortBy((* : String, rank: Int) => rank)(Ordering[Int].reverse)

  /* Compute an inverted index of the set of articles, mapping each language
   * to the Wikipedia pages in which it occurs.
   */
  def makeIndex(makeIndexlangs: List[String], rdd: RDD[WikipediaArticle]): RDD[(String, Iterable[WikipediaArticle])] =
    val emptyList = Iterable[WikipediaArticle]()
    def mergeArticles(acc: Iterable[WikipediaArticle], article: WikipediaArticle): Iterable[WikipediaArticle] = acc ++ Iterable(article)
    def combineLists(acc1: Iterable[WikipediaArticle], acc2: Iterable[WikipediaArticle]): Iterable[WikipediaArticle] = acc1 ++ acc2

    val resultRDD = rdd.flatMap(article => {
      makeIndexlangs.filter(lang => article.mentionsLanguage(lang)).map(lang => (lang, article))
    }).aggregateByKey(emptyList)(mergeArticles, combineLists)

    resultRDD

  /* (2) Compute the language ranking again, but now using the inverted index. Can you notice
   *     a performance improvement?
   *
   *   Note: this operation is long-running. It can potentially run for
   *   several seconds.
   */
  def rankLangsUsingIndex(index: RDD[(String, Iterable[WikipediaArticle])]): List[(String, Int)] =
    index.mapValues(v => v.size).sortBy(v => v._2, ascending = false).collect().toList

  /* (3) Use `reduceByKey` so that the computation of the index and the ranking are combined.
   *     Can you notice an improvement in performance compared to measuring *both* the computation of the index
   *     and the computation of the ranking? If so, can you think of a reason?
   *
   *   Note: this operation is long-running. It can potentially run for
   *   several seconds.
   */
  def rankLangsReduceByKey(langs: List[String], rdd: RDD[WikipediaArticle]): List[(String, Int)] =
    def sumValues(a: Int, b: Int): Int = a + b
    rdd.flatMap(article => {
      langs.filter(lang => article.mentionsLanguage(lang)).map(lang => (lang, 1))
    }).reduceByKey(sumValues).sortBy(v => v._2, ascending = false)
      .collect()
      .toList

  def main(args: Array[String]): Unit =

    /* Languages ranked according to (1) */
    val langsRanked: List[(String, Int)] = timed("Part 1: naive ranking", rankLangs(langs, wikiRdd))

    /* An inverted index mapping languages to wikipedia pages on which they appear */
    def index: RDD[(String, Iterable[WikipediaArticle])] = makeIndex(langs, wikiRdd)

    /* Languages ranked according to (2), using the inverted index */
    val langsRanked2: List[(String, Int)] = timed("Part 2: ranking using inverted index", rankLangsUsingIndex(index))

    /* Languages ranked according to (3) */
    val langsRanked3: List[(String, Int)] = timed("Part 3: ranking using reduceByKey", rankLangsReduceByKey(langs, wikiRdd))

    /* Output the speed of each ranking */
    println(timing)
    sc.stop()

  val timing = new StringBuffer
  def timed[T](label: String, code: => T): T =
    val start = System.currentTimeMillis()
    val result = code
    val stop = System.currentTimeMillis()
    timing.append(s"Processing $label took ${stop - start} ms.\n")
    result
