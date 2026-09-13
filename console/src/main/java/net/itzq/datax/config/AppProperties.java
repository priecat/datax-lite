package net.itzq.datax.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "datax-console")
public class AppProperties {

    /** 最大同时执行任务数 */
    private int maxConcurrentJobs = 3;

    /** 定时调度线程池大小 */
    private int schedulePoolSize = 5;

    /**
     * 长跑/卡死告警阈值（分钟）。任务排队超过该时长仍未开始、或开始后长时间无数据进展时输出 ERROR 日志。
     * 仅告警、不自动停止（避免中断正在写数据的作业）；&lt;=0 表示关闭。
     */
    private int jobWarnMinutes = 120;

    /** 登录认证配置 */
    private Auth auth = new Auth();

    @Data
    public static class Auth {

        /** JWT 签名密钥 */
        private String secret = "datax-console@jwt#2024-secret-please-change-me";

        /** token 有效期（小时） */
        private int expireHours = 24;

        /** 用户表为空时自动初始化管理员账号 */
        private InitAdmin initAdmin = new InitAdmin();

        @Data
        public static class InitAdmin {

            private boolean enabled = true;
            private String account = "admin";
            private String password = "admin123";
            private String name = "管理员";
        }
    }
}
