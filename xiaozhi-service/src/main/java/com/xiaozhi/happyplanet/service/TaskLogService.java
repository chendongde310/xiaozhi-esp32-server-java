package com.xiaozhi.happyplanet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.PlanetTaskDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.TaskLogDO;
import com.xiaozhi.happyplanet.dal.mysql.mapper.TaskLogMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 星球任务行为账本服务：任务的“分配 / 完成 / 观察回音”留痕，是
 * 去重（M1）、地球观察日志与回响（M2）、设备寄达标记（M3）、行为画像（M5）的共同底座。
 *
 * <p>与 {@link EnergyLogService} 同样的“尽力而为”写入策略：任何异常都被吞掉并告警，
 * 绝不影响主对话 / 任务完成路径。
 */
@Slf4j
@Service
public class TaskLogService {

    public static final String STATUS_ASSIGNED = "assigned";
    public static final String STATUS_DONE = "done";

    public static final String SOURCE_SCHEDULER = "scheduler";
    public static final String SOURCE_CHAT = "chat";
    public static final String SOURCE_PROACTIVE = "proactive";

    /** 任务去重回溯窗口（天）：分配时避开最近这么多天做过的任务。 */
    public static final int DEDUP_WINDOW_DAYS = 7;

    @Resource
    private TaskLogMapper taskLogMapper;

    // ==================== 写入：分配 / 完成 / 回音 / 送达 ====================

    /** 记录一次任务分配（写入一条 assigned 行）。source 见 SOURCE_*。 */
    public void recordAssigned(String deviceId, Integer roleId, PlanetTaskDO task, String source, LocalDate assignDate) {
        if (deviceId == null || roleId == null || task == null) {
            return;
        }
        try {
            TaskLogDO row = new TaskLogDO();
            row.setDeviceId(deviceId);
            row.setRoleId(roleId);
            row.setTaskId(task.getId());
            row.setTaskContent(task.getContent());
            row.setCategory(task.getCategory());
            row.setChainKey(task.getChainKey());
            row.setChainSeq(task.getChainSeq());
            row.setSource(source == null ? SOURCE_CHAT : source);
            row.setStatus(STATUS_ASSIGNED);
            row.setAssignDate(assignDate == null ? LocalDate.now() : assignDate);
            taskLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("记录任务分配失败 deviceId={}, roleId={}, taskId={}", deviceId, roleId, task.getId(), e);
        }
    }

    /**
     * 标记某日的任务为已完成：优先更新当日该任务的 assigned 行；找不到（历史遗留/未留痕）则补插一条 done 行。
     */
    public void markDone(String deviceId, Integer roleId, Long taskId, LocalDate assignDate, String taskContent, String category) {
        if (deviceId == null || roleId == null) {
            return;
        }
        try {
            TaskLogDO row = findTodayRow(deviceId, roleId, taskId, assignDate);
            if (row != null) {
                row.setStatus(STATUS_DONE);
                row.setDoneTime(LocalDateTime.now());
                taskLogMapper.updateById(row);
                return;
            }
            TaskLogDO fresh = new TaskLogDO();
            fresh.setDeviceId(deviceId);
            fresh.setRoleId(roleId);
            fresh.setTaskId(taskId);
            fresh.setTaskContent(taskContent);
            fresh.setCategory(category);
            fresh.setSource(SOURCE_CHAT);
            fresh.setStatus(STATUS_DONE);
            fresh.setAssignDate(assignDate == null ? LocalDate.now() : assignDate);
            fresh.setDoneTime(LocalDateTime.now());
            taskLogMapper.insert(fresh);
        } catch (Exception e) {
            log.warn("标记任务完成失败 deviceId={}, roleId={}, taskId={}", deviceId, roleId, taskId, e);
        }
    }

    /** 在当日任务行上挂“观察回音”（用户口述的发现），透明可删。空回音跳过。 */
    public void recordEcho(String deviceId, Integer roleId, Long taskId, LocalDate assignDate, String echo, String imageUrl) {
        if (deviceId == null || roleId == null || (!StringUtils.hasText(echo) && !StringUtils.hasText(imageUrl))) {
            return;
        }
        try {
            TaskLogDO row = findTodayRow(deviceId, roleId, taskId, assignDate);
            if (row == null) {
                return;
            }
            if (StringUtils.hasText(echo)) {
                row.setEcho(echo.length() > 500 ? echo.substring(0, 500) : echo);
            }
            if (StringUtils.hasText(imageUrl)) {
                row.setEchoImageUrl(imageUrl);
            }
            taskLogMapper.updateById(row);
        } catch (Exception e) {
            log.warn("记录观察回音失败 deviceId={}, roleId={}, taskId={}", deviceId, roleId, taskId, e);
        }
    }

    /** 把当日任务行的来源标记为“设备主动寄达”（M3：主动搭话把今日任务送到）。 */
    public void markDeliveredProactive(String deviceId, Integer roleId, LocalDate assignDate) {
        if (deviceId == null || roleId == null) {
            return;
        }
        try {
            TaskLogDO row = findTodayRow(deviceId, roleId, null, assignDate);
            if (row != null && !STATUS_DONE.equals(row.getStatus())) {
                row.setSource(SOURCE_PROACTIVE);
                taskLogMapper.updateById(row);
            }
        } catch (Exception e) {
            log.debug("标记任务设备寄达失败 deviceId={}, roleId={}", deviceId, roleId, e);
        }
    }

    /** 删除一条观察日志（用户/管理端“清除这条记录”）。仅限本设备角色，返回删除条数。 */
    public int deleteLog(String deviceId, Integer roleId, Long id) {
        if (deviceId == null || roleId == null || id == null) {
            return 0;
        }
        return taskLogMapper.delete(new LambdaQueryWrapper<TaskLogDO>()
                .eq(TaskLogDO::getId, id)
                .eq(TaskLogDO::getDeviceId, deviceId)
                .eq(TaskLogDO::getRoleId, roleId));
    }

    // ==================== 读取：去重 / 日志 / 回响 / 明细 ====================

    /** 最近 {@link #DEDUP_WINDOW_DAYS} 天内出现过的任务 id（用于分配去重）。 */
    public Set<Long> recentTaskIds(String deviceId, Integer roleId, LocalDate today) {
        LocalDate from = today.minusDays(DEDUP_WINDOW_DAYS);
        Set<Long> ids = new LinkedHashSet<>();
        try {
            for (TaskLogDO r : taskLogMapper.selectList(new LambdaQueryWrapper<TaskLogDO>()
                    .eq(TaskLogDO::getDeviceId, deviceId)
                    .eq(TaskLogDO::getRoleId, roleId)
                    .ge(TaskLogDO::getAssignDate, from)
                    .isNotNull(TaskLogDO::getTaskId))) {
                if (r.getTaskId() != null) {
                    ids.add(r.getTaskId());
                }
            }
        } catch (Exception e) {
            log.warn("查询近期任务失败 deviceId={}, roleId={}", deviceId, roleId, e);
        }
        return ids;
    }

    /** 最近的观察回音（供动态提示词“回响”注入）。仅取有 echo 的 done 行，最新在前。 */
    public List<String> recentEchoes(String deviceId, Integer roleId, int limit) {
        List<String> echoes = new ArrayList<>();
        try {
            for (TaskLogDO r : taskLogMapper.selectList(new LambdaQueryWrapper<TaskLogDO>()
                    .eq(TaskLogDO::getDeviceId, deviceId)
                    .eq(TaskLogDO::getRoleId, roleId)
                    .isNotNull(TaskLogDO::getEcho)
                    .ne(TaskLogDO::getEcho, "")
                    .orderByDesc(TaskLogDO::getDoneTime)
                    .last("limit " + Math.max(1, Math.min(limit, 10))))) {
                if (StringUtils.hasText(r.getEcho())) {
                    echoes.add(r.getEcho());
                }
            }
        } catch (Exception e) {
            log.warn("查询观察回音失败 deviceId={}, roleId={}", deviceId, roleId, e);
        }
        return echoes;
    }

    /** 地球观察日志时间线：已完成的任务（优先带回音），最新在前，供 App/管理端展示。 */
    public List<Map<String, Object>> timeline(String deviceId, Integer roleId, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            for (TaskLogDO r : taskLogMapper.selectList(new LambdaQueryWrapper<TaskLogDO>()
                    .eq(TaskLogDO::getDeviceId, deviceId)
                    .eq(TaskLogDO::getRoleId, roleId)
                    .eq(TaskLogDO::getStatus, STATUS_DONE)
                    .orderByDesc(TaskLogDO::getDoneTime)
                    .last("limit " + Math.max(1, Math.min(limit, 100))))) {
                list.add(toView(r));
            }
        } catch (Exception e) {
            log.warn("查询观察日志失败 deviceId={}, roleId={}", deviceId, roleId, e);
        }
        return list;
    }

    /** 某周期内完成的任务明细（供周报“本周做过哪些任务/发现”）。 */
    public List<Map<String, Object>> doneBetween(String deviceId, Integer roleId, LocalDate start, LocalDate end) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            for (TaskLogDO r : taskLogMapper.selectList(new LambdaQueryWrapper<TaskLogDO>()
                    .eq(TaskLogDO::getDeviceId, deviceId)
                    .eq(TaskLogDO::getRoleId, roleId)
                    .eq(TaskLogDO::getStatus, STATUS_DONE)
                    .ge(TaskLogDO::getAssignDate, start)
                    .le(TaskLogDO::getAssignDate, end)
                    .orderByAsc(TaskLogDO::getDoneTime))) {
                list.add(toView(r));
            }
        } catch (Exception e) {
            log.warn("查询周期任务明细失败 deviceId={}, roleId={}", deviceId, roleId, e);
        }
        return list;
    }

    private Map<String, Object> toView(TaskLogDO r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("taskContent", r.getTaskContent());
        m.put("category", r.getCategory());
        m.put("chainKey", r.getChainKey());
        m.put("chainSeq", r.getChainSeq());
        m.put("source", r.getSource());
        m.put("echo", r.getEcho());
        m.put("echoImageUrl", r.getEchoImageUrl());
        m.put("assignDate", r.getAssignDate() != null ? r.getAssignDate().toString() : null);
        m.put("doneTime", r.getDoneTime() != null ? r.getDoneTime().toString() : null);
        return m;
    }

    // ==================== 行为画像（M5） ====================

    /**
     * 行为画像：近 {@code days} 天各分类的分配/完成计数、完成率、习惯完成时段、最偏好分类。
     * 用于管理端报表与任务个性化推荐。
     */
    public Map<String, Object> stats(String deviceId, Integer roleId, int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, int[]> perCat = new LinkedHashMap<>(); // category -> [assigned, done]
        int[] hourHist = new int[24];
        int totalAssigned = 0;
        int totalDone = 0;
        try {
            LocalDate from = LocalDate.now().minusDays(Math.max(1, days));
            List<TaskLogDO> rows = taskLogMapper.selectList(new LambdaQueryWrapper<TaskLogDO>()
                    .eq(TaskLogDO::getDeviceId, deviceId)
                    .eq(TaskLogDO::getRoleId, roleId)
                    .ge(TaskLogDO::getAssignDate, from));
            for (TaskLogDO r : rows) {
                String cat = r.getCategory() == null ? "other" : r.getCategory();
                int[] c = perCat.computeIfAbsent(cat, k -> new int[2]);
                c[0]++;
                totalAssigned++;
                if (STATUS_DONE.equals(r.getStatus())) {
                    c[1]++;
                    totalDone++;
                    if (r.getDoneTime() != null) {
                        hourHist[r.getDoneTime().getHour()]++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("统计任务画像失败 deviceId={}, roleId={}", deviceId, roleId, e);
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        String topCategory = null;
        int topDone = -1;
        for (Map.Entry<String, int[]> e : perCat.entrySet()) {
            int assigned = e.getValue()[0];
            int done = e.getValue()[1];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", e.getKey());
            m.put("assigned", assigned);
            m.put("done", done);
            m.put("rate", assigned == 0 ? 0 : Math.round(done * 100f / assigned));
            categories.add(m);
            if (done > topDone) {
                topDone = done;
                topCategory = done > 0 ? e.getKey() : topCategory;
            }
        }

        int habitHour = -1;
        int best = 0;
        for (int h = 0; h < 24; h++) {
            if (hourHist[h] > best) {
                best = hourHist[h];
                habitHour = h;
            }
        }

        out.put("days", days);
        out.put("totalAssigned", totalAssigned);
        out.put("totalDone", totalDone);
        out.put("completionRate", totalAssigned == 0 ? 0 : Math.round(totalDone * 100f / totalAssigned));
        out.put("categories", categories);
        out.put("topCategory", topCategory);
        out.put("habitHour", habitHour); // -1 表示样本不足
        return out;
    }

    /** 近期完成最多的任务分类（用于任务个性化加权）；样本不足返回 null。 */
    public String preferredCategory(String deviceId, Integer roleId, int days) {
        Object top = stats(deviceId, roleId, days).get("topCategory");
        return top instanceof String s ? s : null;
    }

    /** 近期习惯完成时段（0-23 小时）；样本不足返回 -1。 */
    public int habitHour(String deviceId, Integer roleId, int days) {
        Object h = stats(deviceId, roleId, days).get("habitHour");
        return h instanceof Integer i ? i : -1;
    }

    // ==================== 内部 ====================

    /** 找当日的任务行：给定 taskId 时精确匹配，否则取当日最新一条。 */
    private TaskLogDO findTodayRow(String deviceId, Integer roleId, Long taskId, LocalDate assignDate) {
        LocalDate date = assignDate == null ? LocalDate.now() : assignDate;
        LambdaQueryWrapper<TaskLogDO> w = new LambdaQueryWrapper<TaskLogDO>()
                .eq(TaskLogDO::getDeviceId, deviceId)
                .eq(TaskLogDO::getRoleId, roleId)
                .eq(TaskLogDO::getAssignDate, date);
        if (taskId != null) {
            w.eq(TaskLogDO::getTaskId, taskId);
        }
        w.orderByDesc(TaskLogDO::getId).last("limit 1");
        return taskLogMapper.selectOne(w);
    }
}
