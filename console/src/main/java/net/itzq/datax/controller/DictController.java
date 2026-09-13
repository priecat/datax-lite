package net.itzq.datax.controller;

import net.itzq.datax.common.R;
import net.itzq.datax.entity.DictType;
import net.itzq.datax.entity.DictValue;
import net.itzq.datax.entity.TypeMap;
import net.itzq.datax.service.DictService;
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

/**
 * 字段类型字典 + 类型映射规则的管理 API。
 */
@RestController
@RequestMapping("/api")
public class DictController {

    private final DictService service;

    public DictController(DictService service) {
        this.service = service;
    }

    // ── 字典类型 ──
    @GetMapping("/dict/types")
    public R<List<DictType>> dictTypes() {
        return R.ok(service.dictTypes());
    }

    @PostMapping("/dict/types")
    public R<DictType> createDictType(@RequestBody DictType t) {
        return R.ok(service.createDictType(t));
    }

    @PutMapping("/dict/types/{id}")
    public R<DictType> updateDictType(@PathVariable String id, @RequestBody DictType t) {
        return R.ok(service.updateDictType(id, t));
    }

    @DeleteMapping("/dict/types/{id}")
    public R<Void> deleteDictType(@PathVariable String id) {
        service.deleteDictType(id);
        return R.ok();
    }

    // ── 字典值 ──
    @GetMapping("/dict/values")
    public R<List<DictValue>> dictValues(@RequestParam("dictCode") String dictCode) {
        return R.ok(service.dictValues(dictCode));
    }

    @PostMapping("/dict/values")
    public R<DictValue> createDictValue(@RequestBody DictValue v) {
        return R.ok(service.createDictValue(v));
    }

    @PutMapping("/dict/values/{id}")
    public R<DictValue> updateDictValue(@PathVariable String id, @RequestBody DictValue v) {
        return R.ok(service.updateDictValue(id, v));
    }

    @DeleteMapping("/dict/values/{id}")
    public R<Void> deleteDictValue(@PathVariable String id) {
        service.deleteDictValue(id);
        return R.ok();
    }

    // ── 类型映射 ──
    @GetMapping("/type-maps/groups")
    public R<List<String>> typeMapGroups() {
        return R.ok(service.typeMapGroups());
    }

    @GetMapping("/type-maps")
    public R<List<TypeMap>> typeMaps(@RequestParam(value = "group", required = false) String group) {
        return R.ok(service.typeMaps(group));
    }

    @PostMapping("/type-maps")
    public R<TypeMap> createTypeMap(@RequestBody TypeMap m) {
        return R.ok(service.createTypeMap(m));
    }

    @PutMapping("/type-maps/{id}")
    public R<TypeMap> updateTypeMap(@PathVariable String id, @RequestBody TypeMap m) {
        return R.ok(service.updateTypeMap(id, m));
    }

    @DeleteMapping("/type-maps/{id}")
    public R<Void> deleteTypeMap(@PathVariable String id) {
        service.deleteTypeMap(id);
        return R.ok();
    }
}
