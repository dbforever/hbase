/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.client;

import static org.apache.hadoop.hbase.client.ConnectionUtils.getPauseTime;
import static org.apache.hadoop.hbase.client.ConnectionUtils.retries2Attempts;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.ipc.HBaseRpcController;
import org.apache.hadoop.hbase.shaded.com.google.protobuf.ServiceException;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.ClientService;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.ipc.RemoteException;

/**
 * Retry caller for a single request, such as get, put, delete, etc.
 */
@InterfaceAudience.Private
class AsyncSingleRequestRpcRetryingCaller<T> {

  private static final Log LOG = LogFactory.getLog(AsyncSingleRequestRpcRetryingCaller.class);

  @FunctionalInterface
  public interface Callable<T> {
    CompletableFuture<T> call(HBaseRpcController controller, HRegionLocation loc,
        ClientService.Interface stub);
  }

  private final HashedWheelTimer retryTimer;

  private final AsyncConnectionImpl conn;

  private final TableName tableName;

  private final byte[] row;

  private final Callable<T> callable;

  private final long pauseNs;

  private final int maxAttempts;

  private final long operationTimeoutNs;

  private final long rpcTimeoutNs;

  private final int startLogErrorsCnt;

  private final CompletableFuture<T> future;

  private final HBaseRpcController controller;

  private final List<RetriesExhaustedException.ThrowableWithExtraContext> exceptions;

  private final long startNs;

  public AsyncSingleRequestRpcRetryingCaller(HashedWheelTimer retryTimer, AsyncConnectionImpl conn,
      TableName tableName, byte[] row, Callable<T> callable, long pauseNs, int maxRetries,
      long operationTimeoutNs, long rpcTimeoutNs, int startLogErrorsCnt) {
    this.retryTimer = retryTimer;
    this.conn = conn;
    this.tableName = tableName;
    this.row = row;
    this.callable = callable;
    this.pauseNs = pauseNs;
    this.maxAttempts = retries2Attempts(maxRetries);
    this.operationTimeoutNs = operationTimeoutNs;
    this.rpcTimeoutNs = rpcTimeoutNs;
    this.startLogErrorsCnt = startLogErrorsCnt;
    this.future = new CompletableFuture<>();
    this.controller = conn.rpcControllerFactory.newController();
    this.exceptions = new ArrayList<>();
    this.startNs = System.nanoTime();
  }

  private int tries = 1;

  private long elapsedMs() {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
  }

  private static Throwable translateException(Throwable t) {
    if (t instanceof UndeclaredThrowableException && t.getCause() != null) {
      t = t.getCause();
    }
    if (t instanceof RemoteException) {
      t = ((RemoteException) t).unwrapRemoteException();
    }
    if (t instanceof ServiceException && t.getCause() != null) {
      t = translateException(t.getCause());
    }
    return t;
  }

  private void completeExceptionally() {
    future.completeExceptionally(new RetriesExhaustedException(tries, exceptions));
  }

  private void onError(Throwable error, Supplier<String> errMsg,
      Consumer<Throwable> updateCachedLocation) {
    error = translateException(error);
    if (tries > startLogErrorsCnt) {
      LOG.warn(errMsg.get(), error);
    }
    RetriesExhaustedException.ThrowableWithExtraContext qt = new RetriesExhaustedException.ThrowableWithExtraContext(
        error, EnvironmentEdgeManager.currentTime(), "");
    exceptions.add(qt);
    if (error instanceof DoNotRetryIOException || tries >= maxAttempts) {
      completeExceptionally();
      return;
    }
    long delayNs;
    if (operationTimeoutNs > 0) {
      long maxDelayNs = operationTimeoutNs - (System.nanoTime() - startNs);
      if (maxDelayNs <= 0) {
        completeExceptionally();
        return;
      }
      delayNs = Math.min(maxDelayNs, getPauseTime(pauseNs, tries - 1));
    } else {
      delayNs = getPauseTime(pauseNs, tries - 1);
    }
    updateCachedLocation.accept(error);
    tries++;
    retryTimer.newTimeout(new TimerTask() {

      @Override
      public void run(Timeout timeout) throws Exception {
        // always restart from beginning.
        locateThenCall();
      }
    }, delayNs, TimeUnit.NANOSECONDS);
  }

  private void resetController() {
    controller.reset();
    if (rpcTimeoutNs >= 0) {
      controller.setCallTimeout(
        (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(rpcTimeoutNs)));
    }
  }

  private void call(HRegionLocation loc) {
    ClientService.Interface stub;
    try {
      stub = conn.getRegionServerStub(loc.getServerName());
    } catch (IOException e) {
      onError(e,
        () -> "Get async stub to " + loc.getServerName() + " for '" + Bytes.toStringBinary(row)
            + "' in " + loc.getRegionInfo().getEncodedName() + " of " + tableName
            + " failed, tries = " + tries + ", maxAttempts = " + maxAttempts + ", timeout = "
            + TimeUnit.NANOSECONDS.toMillis(operationTimeoutNs) + " ms, time elapsed = "
            + elapsedMs() + " ms",
        err -> conn.getLocator().updateCachedLocations(tableName,
          loc.getRegionInfo().getRegionName(), row, err, loc.getServerName()));
      return;
    }
    resetController();
    callable.call(controller, loc, stub).whenComplete((result, error) -> {
      if (error != null) {
        onError(error,
          () -> "Call to " + loc.getServerName() + " for '" + Bytes.toStringBinary(row) + "' in "
              + loc.getRegionInfo().getEncodedName() + " of " + tableName + " failed, tries = "
              + tries + ", maxAttempts = " + maxAttempts + ", timeout = "
              + TimeUnit.NANOSECONDS.toMillis(operationTimeoutNs) + " ms, time elapsed = "
              + elapsedMs() + " ms",
          err -> conn.getLocator().updateCachedLocations(tableName,
            loc.getRegionInfo().getRegionName(), row, err, loc.getServerName()));
        return;
      }
      future.complete(result);
    });
  }

  private void locateThenCall() {
    conn.getLocator().getRegionLocation(tableName, row, tries > 1).whenComplete((loc, error) -> {
      if (error != null) {
        onError(error,
          () -> "Locate '" + Bytes.toStringBinary(row) + "' in " + tableName + " failed, tries = "
              + tries + ", maxAttempts = " + maxAttempts + ", timeout = "
              + TimeUnit.NANOSECONDS.toMillis(operationTimeoutNs) + " ms, time elapsed = "
              + elapsedMs() + " ms",
          err -> {
          });
        return;
      }
      call(loc);
    });
  }

  public CompletableFuture<T> call() {
    locateThenCall();
    return future;
  }
}
