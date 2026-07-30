package com.agenttrail.support;

/**
 * 集成测试连接的 MySQL —— 这台开发机上常驻的实例（3306 端口），不是 Testcontainers
 * 现拉现起的一次性容器。
 *
 * <p>本机上 Testcontainers 起一个全新 MySQL 容器要等 initdb 初始化数据目录，
 * 实测数分钟量级，而这台机器已经有一个常驻运行的 MySQL 实例——直接连它，
 * 各集成测试之间的隔离靠"各自建/清自己的库或表"，不靠"各自起一个新容器"。
 *
 * <p>{@code agenttrail} 是专门为本项目建的库，和这个实例上的其他项目库并列，互不影响。
 */
public final class SharedMySql {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final String DATABASE = "agenttrail";

    private SharedMySql() {
    }

    public static String jdbcUrl() {
        return "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8".formatted(
                HOST, PORT, DATABASE);
    }

    public static String username() {
        return "root";
    }

    public static String password() {
        return "root";
    }
}
