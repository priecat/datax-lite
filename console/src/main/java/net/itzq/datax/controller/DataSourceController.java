package net.itzq.datax.controller;

import net.itzq.datax.common.R;
import net.itzq.datax.dto.ColumnMeta;
import net.itzq.datax.dto.TableMeta;
import net.itzq.datax.entity.DataSource;
import net.itzq.datax.service.DataSourceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

    private final DataSourceService service;

    public DataSourceController(DataSourceService service) {
        this.service = service;
    }

    @GetMapping
    public R<List<DataSource>> list() {
        return R.ok(service.list());
    }

    @GetMapping("/{id}")
    public R<DataSource> get(@PathVariable String id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    public R<DataSource> create(@RequestBody DataSource ds) {
        return R.ok(service.create(ds));
    }

    @PutMapping("/{id}")
    public R<DataSource> update(@PathVariable String id, @RequestBody DataSource ds) {
        return R.ok(service.update(id, ds));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        service.delete(id);
        return R.ok();
    }

    /** 测试连接（不要求已保存） */
    @PostMapping("/test")
    public R<Void> test(@RequestBody DataSource ds) {
        service.test(ds);
        return R.ok();
    }

    /** 测试已保存的数据源 */
    @PostMapping("/{id}/test")
    public R<Void> testSaved(@PathVariable String id) {
        service.testSaved(id);
        return R.ok();
    }

    @GetMapping("/{id}/databases")
    public R<List<String>> databases(@PathVariable String id) {
        return R.ok(service.databases(id));
    }

    @GetMapping("/{id}/tables")
    public R<List<TableMeta>> tables(@PathVariable String id, @RequestParam String db) {
        return R.ok(service.tables(id, db));
    }

    @GetMapping("/{id}/columns")
    public R<List<ColumnMeta>> columns(@PathVariable String id, @RequestParam String db, @RequestParam String table) {
        return R.ok(service.columns(id, db, table));
    }
}
