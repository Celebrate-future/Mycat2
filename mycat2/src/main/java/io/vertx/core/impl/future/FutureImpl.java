/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.impl.future;

import io.mycat.commands.VertxMySQLDatasourcePoolImpl;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.NoStackTraceThrowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Future implementation.
 *
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
class FutureImpl<T> extends FutureBase<T> {
  private static final Logger LOGGER = LoggerFactory.getLogger(FutureImpl.class);

  private static final Object NULL_VALUE = new Object();

  private Object value;
  private Listener<T> listener;

  /**
   * Create a future that hasn't completed yet
   */
  FutureImpl() {
    super();
  }

  /**
   * Create a future that hasn't completed yet
   */
  FutureImpl(ContextInternal context) {
    super(context);
  }

  /**
   * The result of the operation. This will be null if the operation failed.
   */
  public synchronized T result() {
    return value instanceof Throwable ? null : value == NULL_VALUE ? null : (T) value;
  }

  /**
   * An exception describing failure. This will be null if the operation succeeded.
   */
  public synchronized Throwable cause() {
    return value instanceof Throwable ? (Throwable) value : null;
  }

  /**
   * Did it succeed?
   */
  public synchronized boolean succeeded() {
    return value != null && !(value instanceof Throwable);
  }

  /**
   * Did it fail?
   */
  public synchronized boolean failed() {
    return value instanceof Throwable;
  }

  /**
   * Has it completed?
   */
  public synchronized boolean isComplete() {
    return value != null;
  }

  @Override
  public Future<T> onSuccess(Handler<T> handler) {
    Objects.requireNonNull(handler, "No null handler accepted");
    addListener(new Listener<T>() {
      @Override
      public void onSuccess(T value) {
        handler.handle(value);
      }
      @Override
      public void onFailure(Throwable failure) {
      }
    });
    return this;
  }

  @Override
  public Future<T> onFailure(Handler<Throwable> handler) {
    Objects.requireNonNull(handler, "No null handler accepted");
    addListener(new Listener<T>() {
      @Override
      public void onSuccess(T value) {
      }
      @Override
      public void onFailure(Throwable failure) {
        handler.handle(failure);
      }
    });
    return this;
  }

  @Override
  public Future<T> onComplete(Handler<AsyncResult<T>> handler) {
    Objects.requireNonNull(handler, "No null handler accepted");
    Listener<T> listener;
    if (handler instanceof Listener) {
      listener = (Listener<T>) handler;
    } else {
      listener = new Listener<T>() {
        @Override
        public void onSuccess(T value) {
          handler.handle(FutureImpl.this);
        }
        @Override
        public void onFailure(Throwable failure) {
          handler.handle(FutureImpl.this);
        }
      };
    }
    addListener(listener);
    return this;
  }

  @Override
  public void addListener(Listener<T> listener) {
    Object v;
    synchronized (this) {
      v = value;
      if (v == null) {
        if (this.listener == null) {
          this.listener = listener;
        } else {
          ListenerArray<T> listeners;
          if (this.listener instanceof FutureImpl.ListenerArray) {
            listeners = (ListenerArray<T>) this.listener;
          } else {
            listeners = new ListenerArray<>();
            listeners.add(this.listener);
            this.listener = listeners;
          }
          listeners.add(listener);
        }
        return;
      }
    }
    if (v instanceof Throwable) {
      emitFailure((Throwable) v, listener);
    } else {
      if (v == NULL_VALUE) {
        v = null;
      }
      emitSuccess((T) v, listener);
    }
  }

  public boolean tryComplete(T result) {
    Listener<T> l;
    synchronized (this) {
      if (value != null) {
        return false;
      }
      value = result == null ? NULL_VALUE : result;
      l = listener;
      listener = null;
    }
    if (l != null) {
      emitSuccess(result, l);
    }
    return true;
  }

  public boolean tryFail(Throwable cause) {
    if (LOGGER.isDebugEnabled()){
      LOGGER.debug("",cause);
    }
    if (cause == null) {
      cause = new NoStackTraceThrowable(null);
    }
    Listener<T> l;
    synchronized (this) {
      if (value != null) {
        return false;
      }
      value = cause;
      l = listener;
      listener = null;
    }
    if (l != null) {
      emitFailure(cause, l);
    }
    return true;
  }

  @Override
  public String toString() {
    synchronized (this) {
      if (value instanceof Throwable) {
        return "Future{cause=" + ((Throwable)value).getMessage() + "}";
      }
      if (value != null) {
        if (value == NULL_VALUE) {
          return "Future{result=null}";
        }
        StringBuilder sb = new StringBuilder("Future{result=");
        formatValue(value, sb);
        sb.append("}");
        return sb.toString();
      }
      return "Future{unresolved}";
    }
  }

  protected void formatValue(Object value, StringBuilder sb) {
    sb.append(value);
  }

  private static class ListenerArray<T> extends ArrayList<Listener<T>> implements Listener<T> {
    @Override
    public void onSuccess(T value) {
      for (Listener<T> handler : this) {
        handler.onSuccess(value);
      }
    }
    @Override
    public void onFailure(Throwable failure) {
      for (Listener<T> handler : this) {
        handler.onFailure(failure);
      }
    }
  }
}
