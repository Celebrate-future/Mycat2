package io.mycat.monitor;

import com.alibaba.druid.util.JdbcUtils;
import io.mycat.assemble.MycatTest;
import io.mycat.util.JsonUtil;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import lombok.SneakyThrows;
import org.junit.*;
import org.testng.Assert;

import javax.annotation.concurrent.NotThreadSafe;
import java.sql.Connection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@NotThreadSafe
@net.jcip.annotations.NotThreadSafe
public class MycatMonitorTest implements MycatTest {
    public static Vertx vertx;

    @BeforeClass
    public static void beforeClass() {
        if (vertx == null) {
            vertx = Vertx.vertx();
        }

    }

    @AfterClass
    @SneakyThrows
    public static void afterClass() {
        if (vertx != null) {
            vertx.close();
        }
    }


    @Before
    @SneakyThrows
    public synchronized void before() {

    }

    @After
    @SneakyThrows
    public synchronized void after() {
        try (Connection mycatConnection = getMySQLConnection(DB_MYCAT);) {
            JdbcUtils.execute(mycatConnection, "/*+mycat:setSqlTimeFilter{value:" +
                    TimeUnit.SECONDS.toMillis(30) +
                    "} */", Collections.emptyList());
        }
    }
    @Test
    @SneakyThrows
    public void test() {
        Class<DatabaseInstanceEntry.DatabaseInstanceMap> tClass = DatabaseInstanceEntry.DatabaseInstanceMap.class;
        String url = MycatSQLLogMonitorImpl.SHOW_DB_MONITOR_URL;
        DatabaseInstanceEntry.DatabaseInstanceMap b = fetch(url, tClass);
        try (Connection mySQLConnection = getMySQLConnection(DB_MYCAT);) {
            execute(mySQLConnection,RESET_CONFIG);
            execute(mySQLConnection, "CREATE DATABASE if not exists db1");
            execute(mySQLConnection, "CREATE TABLE if not exists db1.`monitor` (\n" +
                    "  `id` bigint(20) NOT NULL KEY " +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4\n");

            String sql = " select * FROM db1.`monitor`; ";
            for (int i = 0; i < 100; i++) {
                JdbcUtils.executeQuery(mySQLConnection, sql, Collections.emptyList());
            }
        }
        DatabaseInstanceEntry.DatabaseInstanceMap f = fetch(url, tClass);
        Assert.assertNotEquals(b.toString(), f.toString());
        System.out.println();
    }

    @Test
    @SneakyThrows
    public void test2() {
        Class<InstanceEntry> tClass = InstanceEntry.class;
        String url = MycatSQLLogMonitorImpl.SHOW_INSTANCE_MONITOR_URL;
        InstanceEntry b = fetch(url, tClass);
        try (Connection mySQLConnection = getMySQLConnection(DB_MYCAT);) {
            execute(mySQLConnection,RESET_CONFIG);
            execute(mySQLConnection, "CREATE DATABASE if not exists db1");
            execute(mySQLConnection, "CREATE TABLE if not exists db1.`monitor` (\n" +
                    "  `id` bigint(20) NOT NULL KEY " +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4\n");

            String sql = " select * FROM db1.`monitor`; ";
            for (int i = 0; i < 100; i++) {
                executeQuery(mySQLConnection, sql);
            }
        }
        InstanceEntry f = fetch(url, tClass);
        Assert.assertNotEquals(b.toString(), f.toString());
        System.out.println();
    }

    @Test
    @SneakyThrows
    public void test3() {
        Class<RWEntry.RWEntryMap> tClass = RWEntry.RWEntryMap.class;
        String url = MycatSQLLogMonitorImpl.SHOW_RW_MONITOR_URL;
        RWEntry.RWEntryMap b = fetch(url, tClass);
        try (Connection mySQLConnection = getMySQLConnection(DB_MYCAT);) {
            execute(mySQLConnection,RESET_CONFIG);
            execute(mySQLConnection, "CREATE DATABASE if not exists db1");
            execute(mySQLConnection, "CREATE TABLE if not exists db1.`monitor` (\n" +
                    "  `id` bigint(20) NOT NULL KEY " +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4\n");

            String sql = " select * FROM db1.`monitor`";
            for (int i = 0; i < 100; i++) {
                executeQuery(mySQLConnection, sql);
            }
        }
        RWEntry.RWEntryMap f = fetch(url, tClass);
        Assert.assertNotEquals(b.toString(), f.toString());
        System.out.println();
    }

    @Test
    @SneakyThrows
    public void test4() {
        try (Connection mySQLConnection = getMySQLConnection(DB_MYCAT);
             Connection prototype = getMySQLConnection(DB1);) {
            execute(prototype, "create database if not exists mycat");
            execute(prototype, "CREATE TABLE if not exists mycat.`sql_log` (\n" +
                    "\t`instanceId` bigint(20) DEFAULT NULL,\n" +
                    "\t`user` varchar(64) DEFAULT NULL,\n" +
                    "\t`connectionId` bigint(20) DEFAULT NULL,\n" +
                    "\t`ip` varchar(22) DEFAULT NULL,\n" +
                    "\t`port` bigint(20) DEFAULT NULL,\n" +
                    "\t`traceId` varchar(22) NOT NULL,\n" +
                    "\t`hash` varchar(22) DEFAULT NULL,\n" +
                    "\t`sqlType` varchar(22) DEFAULT NULL,\n" +
                    "\t`sql` longtext,\n" +
                    "\t`transactionId` varchar(22) DEFAULT NULL,\n" +
                    "\t`sqlTime` bigint(20) DEFAULT NULL,\n" +
                    "\t`responseTime` datetime DEFAULT NULL,\n" +
                    "\t`affectRow` int(11) DEFAULT NULL,\n" +
                    "\t`result` tinyint(1) DEFAULT NULL,\n" +
                    "\t`externalMessage` tinytext,\n" +
                    "\tPRIMARY KEY (`traceId`)\n" +
                    ") ENGINE = InnoDB CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;");
            deleteData(prototype, "mycat", "sql_log");
            List<Map<String, Object>> maps = JdbcUtils.executeQuery(mySQLConnection, "/*+mycat:getSqlTimeFilter{} */", Collections.emptyList());
            Object orginalValue = maps.get(0).get("value");
            // String sql = " select sleep(1)";
            String sql = " select * FROM `performance_schema`.`accounts`; ";
            Assert.assertEquals(0, count(prototype, "mycat", "sql_log"));

            JdbcUtils.execute(mySQLConnection, "/*+mycat:setSqlTimeFilter{value:0} */", Collections.emptyList());
            maps = JdbcUtils.executeQuery(mySQLConnection, "/*+mycat:getSqlTimeFilter{} */", Collections.emptyList());
            Assert.assertEquals("[{value=0}]", maps.toString());
            JdbcUtils.executeQuery(mySQLConnection, sql, Collections.emptyList());
            Thread.sleep(1000);
            long count = count(prototype, "mycat", "sql_log");
            Assert.assertEquals(1, count);
            System.out.println();
        }

    }

    @SneakyThrows
    public static <T> T fetch(String url, Class<T> tClass) {
        Future<T> future = Future.future(promise -> {
            HttpClient httpClient1 = vertx.createHttpClient();
            Future<HttpClientRequest> request1 = httpClient1.request(HttpMethod.GET, 9066, "127.0.0.1", url);
            request1.onSuccess(clientRequest -> clientRequest.response(ar -> {
                if (ar.succeeded()) {
                    HttpClientResponse response = ar.result();
                    response.bodyHandler(event -> {
                        String s = event.toString();
                        T instanceEntry = JsonUtil.from(s, tClass);
                        promise.tryComplete(instanceEntry);
                    });
                } else {
                    promise.tryComplete();
                }
            }).end());
            request1.onFailure(new Handler<Throwable>() {
                @Override
                public void handle(Throwable throwable) {
                    promise.fail(throwable);
                }
            });
        });
        return future.toCompletionStage().toCompletableFuture().get();
    }
}
