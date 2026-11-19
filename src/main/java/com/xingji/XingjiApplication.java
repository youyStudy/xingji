package com.xingji;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // 开启定时任务,让 MediaCleanupJob 的 @Scheduled 生效
public class XingjiApplication {

    public static void main(String[] args) {
        SpringApplication.run(XingjiApplication.class, args);
    }
}
