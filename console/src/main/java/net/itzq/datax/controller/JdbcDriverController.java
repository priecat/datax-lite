package net.itzq.datax.controller;

import net.itzq.datax.common.R;
import net.itzq.datax.jdbc.JdbcDriverHolder;
import net.itzq.datax.jdbc.JdbcDriverInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 外置 JDBC 驱动列表：供前端数据源表单的驱动下拉与部署排查。
 */
@RestController
@RequestMapping("/api/jdbc-drivers")
public class JdbcDriverController {

    private final JdbcDriverHolder holder;

    public JdbcDriverController(JdbcDriverHolder holder) {
        this.holder = holder;
    }

    @GetMapping
    public R<List<JdbcDriverInfo>> list() {
        return R.ok(holder.list());
    }

    /** 重新扫描 jdbc/ 目录（部署者放入新 jar 后无需重启） */
    @PostMapping("/scan")
    public R<List<JdbcDriverInfo>> scan() {
        return R.ok(holder.scan());
    }
}
