package utils

import scala.collection.immutable.Queue


/**
 * Created by sumnulu on 04/03/15.
 */
class RateLimit(n: Int, perSecond: Int) {

  private val rateCounter = new RateCounter(perSecond)

  /**
   * increases counter and return true if call did not exceeded rate limit
   * @return true if under the limit
   */
  def tickAndCheck = tick < n

  /**
   *
   * @return current rate
   */
  def tick: Int = rateCounter.tick()

  /**
   * does not increases call counter
   * @return true if under the limit
   */
  def check = rateCounter.getRate < n

  def getRate = rateCounter.getRate

}


class RateCounter(forDurationSeconds: Int) {
  private val duration: Long = forDurationSeconds * 1000

  var callTimeStamps = Queue[Long]()


  def tick(): Int = {
    callTimeStamps = callTimeStamps enqueue now
    getRate
  }

  def getRate: Int = {
    if (callTimeStamps.size > 0) {
      val (ts, rest) = callTimeStamps.dequeue

      if (now - ts > duration) {
        //remove element
        callTimeStamps = rest

        getRate
      }
      else callTimeStamps.size

    } else 0
  }

  private def now = System.currentTimeMillis

}