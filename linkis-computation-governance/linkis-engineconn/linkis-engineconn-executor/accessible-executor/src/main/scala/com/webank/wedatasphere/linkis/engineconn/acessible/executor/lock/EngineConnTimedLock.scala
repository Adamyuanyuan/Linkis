/*
 * Copyright 2019 WeBank
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webank.wedatasphere.linkis.engineconn.acessible.executor.lock

import java.util.concurrent.{ScheduledFuture, ScheduledThreadPoolExecutor, Semaphore, TimeUnit}

import com.webank.wedatasphere.linkis.common.utils.Logging
import com.webank.wedatasphere.linkis.engineconn.acessible.executor.entity.AccessibleExecutor
import com.webank.wedatasphere.linkis.engineconn.acessible.executor.listener.event.ExecutorUnLockEvent
import com.webank.wedatasphere.linkis.engineconn.executor.listener.ExecutorListenerBusContext
import com.webank.wedatasphere.linkis.manager.common.entity.enumeration.NodeStatus

class EngineConnTimedLock(private var timeout: Long) extends TimedLock with Logging {

  var lock = new Semaphore(1)
  val releaseScheduler = new ScheduledThreadPoolExecutor(1)
  var releaseTask: ScheduledFuture[_] = null
  var lastLockTime: Long = 0
  var lockedBy: AccessibleExecutor = null

  override def acquire(executor: AccessibleExecutor): Unit = {
    lock.acquire()
    lastLockTime = System.currentTimeMillis()
    lockedBy = executor
    scheduleTimeout
  }

  override def tryAcquire(executor: AccessibleExecutor): Boolean = {
    if (null == executor) return false
    val succeed = lock.tryAcquire()
    debug("try to lock for succeed is  " + succeed.toString)
    if (succeed) {
      lastLockTime = System.currentTimeMillis()
      lockedBy = executor
      debug("try to lock for add time out task ! Locked by thread : " + lockedBy.getId)
      scheduleTimeout
    }
    succeed
  }

  // Unlock callback is not called in release method, because release method is called actively
  override def release(): Unit = {
    debug("try to release for lock," + lockedBy + ",current thread " + Thread.currentThread().getName)
    if (lockedBy != null) {
      //&& lockedBy == Thread.currentThread()   Inconsistent thread(线程不一致)
      debug("try to release for lockedBy and thread ")
      if (releaseTask != null) {
        releaseTask.cancel(true)
        releaseTask = null
        releaseScheduler.purge()
      }
      debug("try to release for lock release success")
      lockedBy = null
    }
    resetLock()
  }

  private def resetLock(): Unit = {
    lock.release()
    lock = new Semaphore(1)
  }

  override def forceRelease(): Unit = {
    if (isAcquired()) {
      if (releaseTask != null) {
        releaseTask.cancel(true)
        releaseTask = null
        releaseScheduler.purge()
      }
      lock.release()
      lockedBy = null
    }
    resetLock()
  }

  private def scheduleTimeout: Unit = {
    synchronized {
      if (null == releaseTask || releaseTask.isDone) {
        releaseTask = releaseScheduler.schedule(new Runnable {
          override def run(): Unit = {
            synchronized {
              if (isExpired()) {
                lock.release()
                // unlockCallback depends on lockedBy, so lockedBy cannot be set null before unlockCallback
                unlockCallback(lock.toString)
                info(s"Lock : [${lock.toString} was released due to timeout." )
                resetLock()
              }
            }
          }
        }, timeout, TimeUnit.MILLISECONDS)
      }
    }
  }

  override def isAcquired(): Boolean = {
    lock.availablePermits() < 1
  }

  override def isExpired(): Boolean = {
    if (lastLockTime == 0) return false
    System.currentTimeMillis() - lastLockTime > timeout
  }


  override def numOfPending(): Int = {
    lock.getQueueLength
  }

  override def renew(): Boolean = {
    if (lockedBy != null && lockedBy == Thread.currentThread()) {
      if (isAcquired && releaseTask != null) {
        if (releaseTask.cancel(false)) {
          releaseScheduler.purge()
          scheduleTimeout
          lastLockTime = System.currentTimeMillis()
          return true
        }
      }
    }
    false
  }

  override def resetTimeout(timeout: Long): Unit = synchronized {
    if (isAcquired()) {
      if (null != releaseTask && !isExpired()) {
        releaseTask.cancel(true)
        this.timeout = timeout
      }
      scheduleTimeout
    } else {
      error("Lock is not acquired, so cannot be reset-Timeout")
    }
  }

  private def unlockCallback(lockStr: String): Unit = {
    if (null != lockedBy) {
      lockedBy.transition(NodeStatus.Unlock)
    }
    ExecutorListenerBusContext.getExecutorListenerBusContext().getEngineConnAsyncListenerBus.post(ExecutorUnLockEvent(null, lockStr.toString))
  }

}