package cc.cc1234.client.curator;

import cc.cc1234.specification.connection.ZookeeperConnection;
import cc.cc1234.specification.connection.ZookeeperConnectionFactory;
import cc.cc1234.specification.connection.ZookeeperParams;
import cc.cc1234.specification.listener.ServerListener;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.AuthInfo;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.api.CuratorEventType;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class CuratorZookeeperConnectionFactory implements ZookeeperConnectionFactory<CuratorFramework> {

    private static final Logger log = LoggerFactory.getLogger(CuratorZookeeperConnectionFactory.class);

    @Override
    public ZookeeperConnection<CuratorFramework> create(ZookeeperParams params) {
        final CuratorFramework client = curatorFramework(params);
        client.start();

        try {
            if (!client.blockUntilConnected(5, TimeUnit.SECONDS)) {
                client.close();
                throw new IllegalStateException("connect timeout");
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException("connect timeout", e);
        }
        return new CuratorZookeeperConnection(params.getId(), client);
    }

    @Override
    public ZookeeperConnection<CuratorFramework> createAsync(ZookeeperParams params, List<ServerListener> listener) {
        final CuratorFramework client = curatorFramework(params);
        client.getConnectionStateListenable().addListener(new ConnectionStateListener() {
            @Override
            public void stateChanged(CuratorFramework client, ConnectionState newState) {
                switch (newState) {
                    case RECONNECTED:
                    case CONNECTED:
                        listener.forEach(l -> l.onConnected(params.getId()));
                        break;
                    case SUSPENDED:
                    case LOST:
                        listener.forEach(l -> l.onReconnecting(params.getId()));
                        break;
                    default:
                        client.close();
                }
            }
        });
        client.getCuratorListenable().addListener((client1, event) -> {
            if (event.getType() == CuratorEventType.CLOSING) {
                listener.forEach(l -> l.onClose(params.getId()));
            }
        });

        client.start();
        try {
            if (!client.blockUntilConnected(3, TimeUnit.SECONDS)) {
                client.close();
                throw new IllegalStateException("connect " + params.getUrl() + " failed");
            }
        } catch (InterruptedException e) {
            client.close();
            throw new IllegalStateException("connect " + params.getUrl() + " failed", e);
        }
        return new CuratorZookeeperConnection(params.getId(), client);
    }

    private CuratorFramework curatorFramework(ZookeeperParams params) {
        final RetryPolicy retryPolicy =
                new ExponentialBackoffRetry(params.getRetryIntervalTime(), params.getMaxRetries());
        final CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                .connectString(params.getUrl())
                .connectionTimeoutMs(params.getConnectionTimeout())
                .sessionTimeoutMs(params.getSessionTimeout())
                .retryPolicy(retryPolicy);

        List<AuthInfo> acls = params.getAclList()
                .stream()
                .filter(s -> !s.isBlank())
                .map(ACLs::parseDigest)
                .toList();
        if (!acls.isEmpty()) {
            builder.authorization(acls)
                    .aclProvider(new ACLProvider() {
                        @Override
                        public List<ACL> getDefaultAcl() {
                            return ZooDefs.Ids.CREATOR_ALL_ACL;
                        }

                        @Override
                        public List<ACL> getAclForPath(String path) {
                            return ZooDefs.Ids.CREATOR_ALL_ACL;
                        }
                    });
        }

        return builder.build();
    }
}
