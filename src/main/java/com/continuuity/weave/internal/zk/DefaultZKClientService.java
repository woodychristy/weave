package com.continuuity.weave.internal.zk;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 */
final class DefaultZKClientService implements ZKClientService {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultZKClientService.class);

  private final String zkStr;
  private final int sessionTimeout;
  private final Watcher connectionWatcher;
  private final AtomicReference<ZooKeeper> zooKeeper;
  private final Function<String, List<ACL>> aclMapper;
  private final Service serviceDelegate;
  private ExecutorService eventExecutor;

  DefaultZKClientService(String zkStr, int sessionTimeout, Watcher connectionWatcher) {
    this.zkStr = zkStr;
    this.sessionTimeout = sessionTimeout;
    this.connectionWatcher = wrapWatcher(connectionWatcher);
    this.zooKeeper = new AtomicReference<ZooKeeper>();

    // TODO: Add ACL
    aclMapper = new Function<String, List<ACL>>() {
      @Override
      public List<ACL> apply(String input) {
        return ZooDefs.Ids.OPEN_ACL_UNSAFE;
      }
    };
    serviceDelegate = new ServiceDelegate();
  }

  @Override
  public OperationFuture<String> create(String path, byte[] data, CreateMode createMode) {
    return create(path, data, createMode, true);
  }

  @Override
  public OperationFuture<String> create(String path,
                                        @Nullable final byte[] data,
                                        final CreateMode createMode,
                                        final boolean createParent) {
    final SettableOperationFuture<String> result = SettableOperationFuture.create(path, eventExecutor);
    getZooKeeper().create(path, data, aclMapper.apply(path), createMode, new AsyncCallback.StringCallback() {
      @Override
      public void processResult(int rc, final String path, final Object ctx, final String name) {
        final StringCallback callback = this;
        KeeperException.Code code = KeeperException.Code.get(rc);
        if (code == KeeperException.Code.OK) {
          result.set(name);
          return;
        }
        if (createParent && code == KeeperException.Code.NONODE) {
          // Create the parent node
          String parentPath = path.substring(0, path.lastIndexOf('/'));
          if (parentPath.isEmpty()) {
            result.setException(new IllegalStateException("Root node not exists."));
            return;
          }
          Futures.addCallback(create(parentPath, null, CreateMode.PERSISTENT, createParent),
                              new FutureCallback<String>() {
            @Override
            public void onSuccess(String result) {
              // Create the requested path again
              getZooKeeper().create(path, data, aclMapper.apply(path), createMode, callback, ctx);
            }

            @Override
            public void onFailure(Throwable t) {
              result.setException(t);
            }
          });
          return;
        }
        // Otherwise, it is an error
        result.setException(KeeperException.create(code));
      }
    }, null);

    return result;
  }

  @Override
  public OperationFuture<Stat> exists(String path) {
    return exists(path, null);
  }

  @Override
  public OperationFuture<Stat> exists(String path, final Watcher watcher) {
    final SettableOperationFuture<Stat> result = SettableOperationFuture.create(path, eventExecutor);
    final Watcher wrappedWatcher = wrapWatcher(watcher);

    getZooKeeper().exists(path, wrappedWatcher, new AsyncCallback.StatCallback() {
      @Override
      public void processResult(int rc, String path, Object ctx, Stat stat) {
        KeeperException.Code code = KeeperException.Code.get(rc);
        if (code == KeeperException.Code.OK || code == KeeperException.Code.NONODE) {
          result.set(stat);
          return;
        }
        result.setException(KeeperException.create(code));
      }
    }, null);

    return result;
  }

  @Override
  public OperationFuture<NodeChildren> getChildren(String path) {
    return getChildren(path, null);
  }

  @Override
  public OperationFuture<NodeChildren> getChildren(String path, Watcher watcher) {
    final SettableOperationFuture<NodeChildren> result = SettableOperationFuture.create(path, eventExecutor);
    final Watcher wrapperWatcher = wrapWatcher(watcher);

    getZooKeeper().getChildren(path, wrapperWatcher, new AsyncCallback.Children2Callback() {
      @Override
      public void processResult(int rc, String path, Object ctx, List<String> children, Stat stat) {
        KeeperException.Code code = KeeperException.Code.get(rc);
        if (code == KeeperException.Code.OK) {
          result.set(new BasicNodeChildren(children, stat));
          return;
        }
        result.setException(KeeperException.create(code));
      }
    }, null);

    return result;
  }

  @Override
  public OperationFuture<NodeData> getData(String path) {
    return getData(path, null);
  }

  @Override
  public OperationFuture<NodeData> getData(String path, Watcher watcher) {
    final SettableOperationFuture<NodeData> result = SettableOperationFuture.create(path, eventExecutor);
    final Watcher wrapperWatcher = wrapWatcher(watcher);

    getZooKeeper().getData(path, wrapperWatcher, new AsyncCallback.DataCallback() {
      @Override
      public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat) {
        KeeperException.Code code = KeeperException.Code.get(rc);
        if (code == KeeperException.Code.OK) {
          result.set(new BasicNodeData(data, stat));
          return;
        }
        result.setException(KeeperException.create(code));
      }
    }, null);

    return result;
  }

  @Override
  public OperationFuture<Stat> setData(String path, byte[] data) {
    return setData(path, data, -1);
  }

  @Override
  public OperationFuture<Stat> setData(final String dataPath, final byte[] data, final int version) {
    final SettableOperationFuture<Stat> result = SettableOperationFuture.create(dataPath, eventExecutor);
    getZooKeeper().setData(dataPath, data, version, new AsyncCallback.StatCallback() {
      @Override
      public void processResult(int rc, String path, Object ctx, Stat stat) {
        KeeperException.Code code = KeeperException.Code.get(rc);
        if (code == KeeperException.Code.OK) {
          result.set(stat);
          return;
        }
        // Otherwise, it is an error
        result.setException(KeeperException.create(code));
      }
    }, null);

    return result;
  }

  @Override
  public OperationFuture<String> delete(String path) {
    return delete(path, -1);
  }

  @Override
  public OperationFuture<String> delete(final String deletePath, final int version) {
    final SettableOperationFuture<String> result = SettableOperationFuture.create(deletePath, eventExecutor);
    getZooKeeper().delete(deletePath, version, new AsyncCallback.VoidCallback() {
      @Override
      public void processResult(int rc, String path, Object ctx) {
        KeeperException.Code code = KeeperException.Code.get(rc);
        if (code == KeeperException.Code.OK) {
          result.set(deletePath);
          return;
        }
        // Otherwise, it is an error
        result.setException(KeeperException.create(code));
      }
    }, null);

    return result;
  }

  @Override
  public Supplier<ZooKeeper> getZooKeeperSupplier() {
    return new Supplier<ZooKeeper>() {
      @Override
      public ZooKeeper get() {
        return getZooKeeper();
      }
    };
  }

  @Override
  public ListenableFuture<State> start() {
    return serviceDelegate.start();
  }

  @Override
  public State startAndWait() {
    return serviceDelegate.startAndWait();
  }

  @Override
  public boolean isRunning() {
    return serviceDelegate.isRunning();
  }

  @Override
  public State state() {
    return serviceDelegate.state();
  }

  @Override
  public ListenableFuture<State> stop() {
    return serviceDelegate.stop();
  }

  @Override
  public State stopAndWait() {
    return serviceDelegate.stopAndWait();
  }

  @Override
  public void addListener(Listener listener, Executor executor) {
    serviceDelegate.addListener(listener, executor);
  }

  private ZooKeeper getZooKeeper() {
    ZooKeeper zk = zooKeeper.get();
    Preconditions.checkArgument(zk != null, "Not connected to zooKeeper.");
    return zk;
  }

  private Watcher wrapWatcher(final Watcher watcher) {
    if (watcher == null) {
      return null;
    }
    return new Watcher() {
      @Override
      public void process(final WatchedEvent event) {
        eventExecutor.execute(new Runnable() {
          @Override
          public void run() {
            try {
              watcher.process(event);
            } catch (Throwable t) {
              LOG.error("Watcher throws exception.", t);
            }
          }
        });
      }
    };
  }

  private final class ServiceDelegate extends AbstractService implements Watcher {

    @Override
    protected void doStart() {
      // A single thread executor
      eventExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(),
                                             new ThreadFactoryBuilder()
                                               .setDaemon(true)
                                               .setNameFormat("zk-client-EventThread")
                                               .build()) {
        @Override
        protected void terminated() {
          super.terminated();
          notifyStopped();
        }
      };

      try {
        zooKeeper.set(new ZooKeeper(zkStr, sessionTimeout, this));
      } catch (IOException e) {
        notifyFailed(e);
      }
    }

    @Override
    protected void doStop() {
      ZooKeeper zk = zooKeeper.getAndSet(null);
      if (zk != null) {
        try {
          zk.close();
        } catch (InterruptedException e) {
          notifyFailed(e);
        } finally {
          eventExecutor.shutdown();
        }
      }
    }

    @Override
    public void process(WatchedEvent event) {
      try {
        if (event.getState() == Event.KeeperState.SyncConnected && state() == State.STARTING) {
          LOG.info("Connected to ZooKeeper: " + zkStr);
          notifyStarted();
          return;
        }
        if (event.getState() == Event.KeeperState.Expired) {
          LOG.info("ZooKeeper session expired: " + zkStr);

          // When connection expired, simple reconnect again
          Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
              try {
                zooKeeper.set(new ZooKeeper(zkStr, sessionTimeout, ServiceDelegate.this));
              } catch (IOException e) {
                zooKeeper.set(null);
                notifyFailed(e);
              }
            }
          }, "zk-reconnect");
          t.setDaemon(true);
          t.start();
        }
      } finally {
        if (connectionWatcher != null && event.getType() == Event.EventType.None) {
          connectionWatcher.process(event);
        }
      }
    }
  }
}
