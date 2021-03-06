/*
 * Copyright 2012-2013 Continuuity,Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.continuuity.weave.internal;

import com.continuuity.weave.internal.state.Message;
import com.continuuity.weave.internal.state.MessageCodec;
import com.continuuity.weave.zookeeper.ZKClient;
import com.continuuity.weave.zookeeper.ZKOperations;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.apache.zookeeper.CreateMode;

/**
 *
 */
public final class ZKMessages {

  /**
   * Creates a message node in zookeeper. The message node created is a PERSISTENT_SEQUENTIAL node.
   *
   * @param zkClient The ZooKeeper client for interacting with ZooKeeper.
   * @param messagePathPrefix ZooKeeper path prefix for the message node.
   * @param message The {@link Message} object for the content of the message node.
   * @param completionResult Object to set to the result future when the message is processed.
   * @param <V> Type of the completion result.
   * @return A {@link ListenableFuture} that will be completed when the message is consumed, which indicated
   *         by deletion of the node. If there is exception during the process, it will be reflected
   *         to the future returned.
   */
  public static <V> ListenableFuture<V> sendMessage(final ZKClient zkClient, String messagePathPrefix,
                                                    Message message, final V completionResult) {
    SettableFuture<V> result = SettableFuture.create();
    sendMessage(zkClient, messagePathPrefix, message, result, completionResult);
    return result;
  }

  /**
   * Creates a message node in zookeeper. The message node created is a PERSISTENT_SEQUENTIAL node.
   *
   * @param zkClient The ZooKeeper client for interacting with ZooKeeper.
   * @param messagePathPrefix ZooKeeper path prefix for the message node.
   * @param message The {@link Message} object for the content of the message node.
   * @param completion A {@link SettableFuture} to reflect the result of message process completion.
   * @param completionResult Object to set to the result future when the message is processed.
   * @param <V> Type of the completion result.
   */
  public static <V> void sendMessage(final ZKClient zkClient, String messagePathPrefix, Message message,
                                     final SettableFuture<V> completion, final V completionResult) {

    // Creates a message and watch for its deletion for completion.
    Futures.addCallback(zkClient.create(messagePathPrefix, MessageCodec.encode(message),
                                        CreateMode.PERSISTENT_SEQUENTIAL), new FutureCallback<String>() {
      @Override
      public void onSuccess(String path) {
        Futures.addCallback(ZKOperations.watchDeleted(zkClient, path), new FutureCallback<String>() {
          @Override
          public void onSuccess(String result) {
            completion.set(completionResult);
          }

          @Override
          public void onFailure(Throwable t) {
            completion.setException(t);
          }
        });
      }

      @Override
      public void onFailure(Throwable t) {
        completion.setException(t);
      }
    });
  }

  private ZKMessages() {
  }
}
