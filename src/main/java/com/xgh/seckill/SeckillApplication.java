package com.xgh.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 分布式高并发秒杀系统启动类
 *
 * @author 向国豪
 */
@SpringBootApplication
@MapperScan("com.xgh.seckill.mapper")
@EnableScheduling // 开启定时任务：本地消息表补偿扫描
public class SeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillApplication.class, args);
        System.out.println("""

                ========================================================
                  秒杀系统启动成功！
                  接口文档: http://localhost:8080/doc.html
                  测试账号: zhangsan / 123456
                ========================================================
                """);
    }
}
