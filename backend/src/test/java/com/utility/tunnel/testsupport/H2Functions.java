package com.utility.tunnel.testsupport;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * H2 回归库函数绑定：把生产 SQL 里的 UTC_TIMESTAMP() 映射到 JVM 当前 UTC 时间。
 */
public final class H2Functions {

    private H2Functions() {
    }

    public static Timestamp utcTimestamp() {
        return Timestamp.from(Instant.now());
    }
}
