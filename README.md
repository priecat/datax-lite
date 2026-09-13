<div align="center">

# DataX Lite

基于 [DataX](https://github.com/alibaba/DataX) 开发的 **轻量化** 数据同步工具。
开箱即用，无需Docker、Python、额外数据库消息队列或调度组件，通过web界面选择源库、目标库、表，即可整库/整表完成全量同步。

**本工具仅支持 MySQL > MySQL 库间对拷** 

</div>



## 快速开始

运行环境：Java8+

从 Releases 下载预构建包或从源码编译

```bash
java -jar datax-lite.jar
```

启动后访问 <http://localhost:8001>，默认管理员账号：`admin` / `admin123`（首次启动自动创建，可在配置文件中修改）。

## 配置说明

主要配置见 `console/src/main/resources/application.yml`：

- `server.port`：服务端口，默认 `8001`
- `datax-console.max-concurrent-jobs`：最大同时执行任务数，默认 `3`
- `datax-console.auth.secret`：JWT 签名密钥，**务必修改**
- `datax-console.auth.init-admin`：用户表为空时自动初始化的管理员账号



## 执行流程

一次同步任务的执行分为 **准备阶段** 和 **DataX 执行阶段**，准备阶段全部完成后才会开始数据传输。

### 阶段一：表结构准备阶段

先集中处理任务涉及的所有目标表，按配置执行新建 / 重建 / 修改：

| 配置 | 准备动作 |
| --- | --- |
| 勾选「重建表」 | 目标表存在则 `DROP TABLE`，再按源表结构 `CREATE TABLE` |
| 目标表不存在 + 勾选「自动建表」 | 按源表结构 `CREATE TABLE` |
| 目标表已存在 | 比对映射字段，缺失的列用 `ALTER TABLE ... ADD COLUMN` 补齐（只增不删，目标表多出的字段不受影响） |
| 目标表不存在且未勾选「自动建表」 | 不建表，执行写入时会失败（界面上会给出警告） |

字段级别的设置只对「自动建表」「重建表」「缺失补齐」触发时才生效。

### 阶段二：DataX 执行数据传输 逐批填充数据

 

## 特别注意

- 准备阶段是**先集中处理整个任务的所有表**，全部处理完才进入传输。这意味着**如果勾选了「清空」「重建表」，目标库中该任务包含的所有表会在数据传输开始前就被清空**。
- 由于准备与传输是两个前后阶段，若任务在传输途中、任务失败或被手动停止，已重建 / 已清空的表会处于空表或数据不完整的状态。请确认目标库可以接受该风险，重要数据请先备份。

 

## 预览


![选择同步对象](docs/task-step2-tables.png)

![执行日志](docs/task-log.png)

