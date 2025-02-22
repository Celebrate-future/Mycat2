/**
 * Copyright (C) <2021>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.datasource.jdbc.datasource;


import com.alibaba.druid.util.JdbcUtils;
import com.mongodb.Mongo;
import io.mycat.ConnectionManager;
import io.mycat.IOExecutor;
import io.mycat.MetaClusterCurrent;
import io.mycat.MycatException;
import io.mycat.api.collector.RowBaseIterator;
import io.mycat.config.ClusterConfig;
import io.mycat.config.DatasourceConfig;
import io.mycat.config.ServerConfig;
import io.mycat.datasource.jdbc.DatasourceProvider;
import io.mycat.datasource.jdbc.DruidDatasourceProvider;
import io.mycat.datasource.jdbc.mongodb.MongoDriver;
import io.mycat.replica.ReplicaSelectorManager;
import io.mycat.replica.heartbeat.HeartBeatStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * @author jamie12221 date 2019-05-10 14:46 该类型需要并发处理
 **/
public class JdbcConnectionManager implements ConnectionManager<DefaultConnection> {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcConnectionManager.class);
    private final ConcurrentHashMap<String, JdbcDataSource> dataSourceMap = new ConcurrentHashMap<>();
    private final DatasourceProvider datasourceProvider;

    public JdbcConnectionManager(String customerDatasourceProvider,
                                 Map<String, DatasourceConfig> datasources) {
        this(datasources, createDatasourceProvider(customerDatasourceProvider));
    }

    public void register(ReplicaSelectorManager replicaSelector) {

        for (ClusterConfig replica : replicaSelector.getConfig()) {
            String replicaName = replica.getName();
            for (String datasource : replica.allDatasources()) {
                putHeartFlow(replicaSelector, replicaName, datasource);
            }
        }
    }

    private static DatasourceProvider createDatasourceProvider(String customerDatasourceProvider) {
        String defaultDatasourceProvider = Optional.ofNullable(customerDatasourceProvider).orElse(DruidDatasourceProvider.class.getName());
        try {
            DatasourceProvider o = (DatasourceProvider) Class.forName(defaultDatasourceProvider)
                    .getDeclaredConstructor().newInstance();
            ServerConfig serverConfig = MetaClusterCurrent.exist(ServerConfig.class) ?
                    MetaClusterCurrent.wrapper(ServerConfig.class) :
                    new ServerConfig();
            o.init(serverConfig);
            return o;
        } catch (Exception e) {
            throw new MycatException("can not load datasourceProvider:{}", customerDatasourceProvider);
        }
    }

    public JdbcConnectionManager(Map<String, DatasourceConfig> datasources,
                                 DatasourceProvider provider) {
        this.datasourceProvider = Objects.requireNonNull(provider);

        for (DatasourceConfig datasource : datasources.values()) {
            if (datasource.computeType().isJdbc()) {
                addDatasource(datasource);
            }
        }
    }

    @Override
    public void addDatasource(DatasourceConfig key) {
        JdbcDataSource jdbcDataSource = dataSourceMap.get(key.getName());
        if (jdbcDataSource != null) {
            jdbcDataSource.close();
        }
        dataSourceMap.put(key.getName(), datasourceProvider.createDataSource(key));
    }

    @Override
    public void removeDatasource(String jdbcDataSourceName) {
        JdbcDataSource remove = dataSourceMap.remove(jdbcDataSourceName);
        if (remove != null) {
            remove.close();
        }
    }

    public DefaultConnection getConnection(String name) {
        return getConnection(name, true, Connection.TRANSACTION_REPEATABLE_READ);
    }

    public static String resolveDataSource(String datasourceName) {
        if (MetaClusterCurrent.exist(ReplicaSelectorManager.class)) {
            ReplicaSelectorManager replicaSelector = MetaClusterCurrent.wrapper(ReplicaSelectorManager.class);
            if (replicaSelector.isDatasource(datasourceName)) {
                return datasourceName;
            }
            if (replicaSelector.isReplicaName(datasourceName)) {
                return replicaSelector.getDatasourceNameByReplicaName(datasourceName, true, null);
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("can not resolve datasourceName:{}", datasourceName);
            }
        }
        return datasourceName;
    }

    public DefaultConnection getConnection(String name, Boolean autocommit,
                                           int transactionIsolation) {
        name = resolveDataSource(name);
        final JdbcDataSource key = dataSourceMap.get(name);
        Objects.requireNonNull(key, "unknown target:" + name);
        DefaultConnection defaultConnection;
        Connection connection = null;
        switch (key.getDbType()) {
            default: {
                try {
                    DatasourceConfig config = key.getConfig();
                    connection = key.getDataSource().getConnection();
                    defaultConnection = new DefaultConnection(connection, key, autocommit, transactionIsolation, this);
                    if (config.isInitSqlsGetConnection()) {
                        if (config.getInitSqls() != null && !config.getInitSqls().isEmpty()) {
                            try (Statement statement = connection.createStatement()) {
                                for (String initSql : config.getInitSqls()) {
                                    statement.execute(initSql);
                                }
                            }
                        }
                    }
                    key.counter.getAndIncrement();
                    LOGGER.debug("get connection:{} pool {} ,used count:{} ", name, defaultConnection,key.counter);
                    return defaultConnection;
                } catch (SQLException e) {
                    if (connection != null) {
                        JdbcUtils.close(connection);
                    }
                    LOGGER.debug("", e);
                    throw new MycatException(e);
                }
            }
            case "mongodb": {
                try {
                    Properties properties = new Properties();
                    String username = key.getUsername();
                    String password = key.getPassword();
                    if (username != null) {
                        properties.setProperty("username", username);
                    }
                    if (password != null) {
                        properties.setProperty("password", password);
                    }
                    Connection connect = new MongoDriver().connect(key.getUrl(), properties);
                    return new DefaultConnection(connect, key, autocommit, transactionIsolation, this);
                } catch (SQLException ex) {
                    throw new MycatException(ex);
                }
            }
        }
    }

    @Override
    public void closeConnection(DefaultConnection connection) {
        JdbcDataSource dataSource = connection.getDataSource();
        connection.getDataSource().counter.decrementAndGet();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("close :{} {}", connection, connection.connection);
        }
        //LOGGER.error("{} {}",connection,connection.connection, new Throwable());
        /**
         *
         * To prevent the transaction from being committed at close time,
         * it is implemented in some databases.
         */
        try {
            if (!connection.getDataSource().isMySQLType()) {
                if (!connection.connection.getAutoCommit()) {
                    LOGGER.error("need rollback in JdbcConnectionManager targetName:{}", connection.getDataSource().getName());
                    connection.connection.rollback();
                    connection.connection.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            LOGGER.error("", e);
        }
        JdbcUtils.close(connection.connection);
        LOGGER.debug("rec connection:{} pool {} ,used count:{} ", dataSource.getName(), connection,dataSource.counter);
    }

    @Override
    public Map<String, DatasourceConfig> getConfigAsMap() {
        HashMap<String, DatasourceConfig> res = new HashMap<>();
        for (Map.Entry<String, JdbcDataSource> entry : dataSourceMap.entrySet()) {
            DatasourceConfig config = entry.getValue().getConfig();
            String key = config.getName();
            res.put(key, config);
        }
        return res;
    }

    @Override
    public List<DatasourceConfig> getConfigAsList() {
        return new ArrayList<>(getConfigAsMap().values());
    }

    @Override
    public void close() {
        for (JdbcDataSource value : dataSourceMap.values()) {
            value.close();
        }

    }

    public Map<String, JdbcDataSource> getDatasourceInfo() {
        return Collections.unmodifiableMap(dataSourceMap);
    }

    private void putHeartFlow(ReplicaSelectorManager replicaSelector, String replicaName, String datasource) {
        replicaSelector.putHeartFlow(replicaName, datasource, new Consumer<HeartBeatStrategy>() {
            @Override
            public void accept(HeartBeatStrategy heartBeatStrategy) {
                IOExecutor vertx = MetaClusterCurrent.wrapper(IOExecutor.class);
                vertx.executeBlocking(promise -> {
                    try {
                        heartbeat(heartBeatStrategy);
                    } catch (Exception e) {
                        heartBeatStrategy.onException(e);
                    } finally {
                        promise.tryComplete();
                    }
                });
            }

            private void heartbeat(HeartBeatStrategy heartBeatStrategy) {
                boolean readOnly = false;
                try (DefaultConnection connection = getConnection(datasource)) {
                    try {
                        readOnly = connection.connection.isReadOnly() || JdbcUtils.executeQuery(connection.connection, "SELECT @@read_only", Collections.emptyList()).toString().contains("1");
                    } catch (Throwable throwable) {
                        LOGGER.debug("heartbeat sql:{}", "SELECT @@read_only", throwable);
                    }
                    ArrayList<List<Map<String, Object>>> resultList = new ArrayList<>();
                    List<String> sqls = heartBeatStrategy.getSqls();
                    for (String sql : sqls) {
                        LOGGER.debug("heartbeat sql:{}", sql);
                        try (RowBaseIterator iterator = connection
                                .executeQuery(sql)) {
                            resultList.add(iterator.getResultSetMap());

                        } catch (Exception e) {
                            LOGGER.error("jdbc heartbeat ", e);
                            return;
                        }
                    }
                    heartBeatStrategy.process(resultList, readOnly);
                } catch (Throwable e) {
                    heartBeatStrategy.onException(e);
                    LOGGER.error("", e);
                }
            }
        });
    }


    public DatasourceProvider getDatasourceProvider() {
        return datasourceProvider;
    }
}
