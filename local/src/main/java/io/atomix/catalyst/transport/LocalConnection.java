/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.atomix.catalyst.transport;

import io.atomix.catalyst.buffer.Buffer;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.Listener;
import io.atomix.catalyst.util.Listeners;
import io.atomix.catalyst.util.ReferenceCounted;
import io.atomix.catalyst.util.concurrent.Futures;
import io.atomix.catalyst.util.concurrent.ThreadContext;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Local connection.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class LocalConnection implements Connection {
  private final UUID id = UUID.randomUUID();
  private final ThreadContext context;
  private final Set<LocalConnection> connections;
  private LocalConnection connection;
  private final Map<Class<?>, HandlerHolder> handlers = new ConcurrentHashMap<>();
  private final Listeners<Throwable> exceptionListeners = new Listeners<>();
  private final Listeners<Connection> closeListeners = new Listeners<>();

  public LocalConnection(ThreadContext context) {
    this(context, null);
  }

  public LocalConnection(ThreadContext context, Set<LocalConnection> connections) {
    this.context = context;
    this.connections = connections;
  }

  /**
   * Connects the connection to another connection.
   */
  public LocalConnection connect(LocalConnection connection) {
    this.connection = connection;
    return this;
  }

  @Override
  public <T, U> CompletableFuture<U> send(T request) {
    Assert.notNull(request, "request");
    ThreadContext context = ThreadContext.currentContextOrThrow();
    CompletableFuture<U> future = new CompletableFuture<>();

    Buffer requestBuffer = context.serializer().writeObject(request);
    connection.<U>receive(requestBuffer.flip()).whenCompleteAsync((responseBuffer, error) -> {
      if (error == null) {
        int status = responseBuffer.readByte();
        if (status == 1) {
          future.complete(context.serializer().readObject(responseBuffer));
        } else {
          future.completeExceptionally(context.serializer().readObject(responseBuffer));
        }
        responseBuffer.release();
      } else {
        future.completeExceptionally(error);
      }
    }, context.executor());

    if (request instanceof ReferenceCounted) {
      ((ReferenceCounted<?>) request).release();
    }
    return future;
  }

  /**
   * Receives a message.
   */
  @SuppressWarnings("unchecked")
  private CompletableFuture<Buffer> receive(Buffer requestBuffer) {
    ThreadContext context = ThreadContext.currentContextOrThrow();

    Object request = context.serializer().readObject(requestBuffer);
    requestBuffer.release();

    HandlerHolder holder = handlers.get(request.getClass());
    if (holder != null) {
      MessageHandler<Object, Object> handler = (MessageHandler<Object, Object>) holder.handler;
      CompletableFuture<Buffer> future = new CompletableFuture<>();

      try {
        holder.context.executor().execute(() -> {
          handler.handle(request).whenCompleteAsync((response, error) -> {
            Buffer responseBuffer = context.serializer().allocate();
            if (error == null) {
              responseBuffer.writeByte(1);
              context.serializer().writeObject(response, responseBuffer);
            } else {
              responseBuffer.writeByte(0);
              context.serializer().writeObject(error, responseBuffer);
            }

            future.complete(responseBuffer.flip());

            if (response instanceof ReferenceCounted) {
              ((ReferenceCounted<?>) response).release();
            }
          }, context.executor());
        });
        return future;
      } catch (RejectedExecutionException e) {
        return Futures.exceptionalFuture(new IllegalStateException("connection closed", e));
      }
    }
    return Futures.exceptionalFuture(new TransportException("no handler registered"));
  }

  @Override
  public <T, U> Connection handler(Class<T> type, MessageHandler<T, U> handler) {
    Assert.notNull(type, "type");
    if (handler != null) {
      handlers.put(type, new HandlerHolder(handler, ThreadContext.currentContextOrThrow()));
    } else {
      handlers.remove(type);
    }
    return this;
  }

  @Override
  public Listener<Throwable> exceptionListener(Consumer<Throwable> listener) {
    return exceptionListeners.add(Assert.notNull(listener, "listener"));
  }

  @Override
  public Listener<Connection> closeListener(Consumer<Connection> listener) {
    return closeListeners.add(Assert.notNull(listener, "listener"));
  }

  @Override
  public CompletableFuture<Void> close() {
    doClose();
    connection.doClose();
    return ThreadContext.currentContextOrThrow().execute(() -> null);
  }

  /**
   * Closes the connection.
   */
  private void doClose() {
    if (connections != null)
      connections.remove(this);

    for (Consumer<Connection> closeListener : closeListeners) {
      context.executor().execute(() -> closeListener.accept(this));
    }
  }

  /**
   * Holds message handler and thread context.
   */
  protected static class HandlerHolder {
    private final MessageHandler<?, ?> handler;
    private final ThreadContext context;

    private HandlerHolder(MessageHandler<?, ?> handler, ThreadContext context) {
      this.handler = handler;
      this.context = context;
    }
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public boolean equals(Object object) {
    return object instanceof LocalConnection && ((LocalConnection) object).id.equals(id);
  }

}
