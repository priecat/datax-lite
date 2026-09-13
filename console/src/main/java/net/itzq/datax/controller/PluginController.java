package net.itzq.datax.controller;

import net.itzq.datax.common.R;
import net.itzq.datax.dto.PluginInfo;
import net.itzq.datax.service.PluginService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginService pluginService;

    public PluginController(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    @GetMapping
    public R<List<PluginInfo>> list() {
        return R.ok(pluginService.listPlugins());
    }
}
