package com.gmsr.scheduler.service;

import com.gmsr.scheduler.domain.Boss;
import com.gmsr.scheduler.domain.BossCombo;
import com.gmsr.scheduler.domain.BossRun;
import com.gmsr.scheduler.domain.BossTier;
import com.gmsr.scheduler.domain.RunMember;
import com.gmsr.scheduler.domain.WeekSchedule;
import com.gmsr.scheduler.dto.WeekDtos.WeekDetail;
import com.gmsr.scheduler.dto.WeekDtos.WeekView;
import com.gmsr.scheduler.repository.BossRepository;
import com.gmsr.scheduler.repository.BossRunRepository;
import com.gmsr.scheduler.repository.RunMemberRepository;
import com.gmsr.scheduler.repository.WeekScheduleRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 排班周期管理。GMSR 的 CD 在每周四刷新，因此一个周期 = 周四 ~ 下周三，
 * 周期标识用该周周四的日期（yyyy-MM-dd）。
 */
@Service
public class WeekService {

    public static final DayOfWeek WEEK_START = DayOfWeek.THURSDAY;

    private static final DateTimeFormatter KEY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    /** 月周期 key 格式：2026-10（按月刷新的 BOSS 用） */
    private static final DateTimeFormatter MONTH_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter LABEL_FORMAT = DateTimeFormatter.ofPattern("MM/dd");

    private final WeekScheduleRepository weekRepository;
    private final BossRepository bossRepository;
    private final BossRunRepository runRepository;
    private final RunMemberRepository memberRepository;

    public WeekService(
            WeekScheduleRepository weekRepository,
            BossRepository bossRepository,
            BossRunRepository runRepository,
            RunMemberRepository memberRepository) {
        this.weekRepository = weekRepository;
        this.bossRepository = bossRepository;
        this.runRepository = runRepository;
        this.memberRepository = memberRepository;
    }

    /** 某一天所在周期的周四。 */
    public static LocalDate weekStartOf(LocalDate date) {
        int diff = (date.getDayOfWeek().getValue() - WEEK_START.getValue() + 7) % 7;
        return date.minusDays(diff);
    }

    public static String keyOf(LocalDate date) {
        return weekStartOf(date).format(KEY_FORMAT);
    }

    public static LocalDate dateOfKey(String weekKey) {
        try {
            return LocalDate.parse(weekKey, KEY_FORMAT);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new BusinessException("排班周期格式不正确（应为 yyyy-MM-dd）：" + weekKey);
        }
    }

    public static String currentKey() {
        return keyOf(LocalDate.now());
    }

    /**
     * 确保周期记录存在。
     *
     * 支持两种 key：
     * <ul>
     *   <li><b>周</b>（2026-10-08）：周四 CD 刷新，一周 = 周四 ~ 下周三；</li>
     *   <li><b>月</b>（2026-10）：按月刷新的 BOSS（老黑能量车）用，一个月算一个周期。</li>
     * </ul>
     * 早期只支持周格式，导致按月 BOSS 新建车次时报「排班周期格式不正确」。
     */
    @Transactional
    public WeekSchedule ensureWeek(String weekKey, String note) {
        boolean monthly = isMonthKey(weekKey);
        WeekSchedule existing = weekRepository.findById(weekKey).orElse(null);
        if (existing != null) {
            if (note != null && !note.equals(existing.getNote())) {
                existing.setNote(note);
                weekRepository.save(existing);
            }
            return existing;
        }
        WeekSchedule week = new WeekSchedule();
        week.setWeekKey(weekKey);
        if (monthly) {
            YearMonth month = YearMonth.parse(weekKey, MONTH_KEY_FORMAT);
            week.setStartDate(month.atDay(1));
            week.setEndDate(month.atEndOfMonth());
            week.setLabel(month.getMonthValue() + " 月");
        } else {
            LocalDate start = dateOfKey(weekKey);
            week.setStartDate(start);
            week.setEndDate(start.plusDays(6));
            week.setLabel(start.format(LABEL_FORMAT) + " ~ " + start.plusDays(6).format(LABEL_FORMAT));
        }
        week.setNote(note);
        return weekRepository.save(week);
    }

    /** 是否是「2026-10」这种月周期 key。 */
    public static boolean isMonthKey(String key) {
        return key != null && key.matches("\\d{4}-\\d{2}");
    }

    @Transactional(readOnly = true)
    public List<WeekView> listWeeks() {
        return weekRepository.findAllByOrderByStartDateDesc().stream().map(this::toView).toList();
    }

    /** 上周 / 本周 / 下周三个周期，缺失的会自动补建。 */
    /**
     * 按月刷新的 BOSS（如老黑能量车）用的周期：上月 / 本月 / 下月。
     *
     * 复用 week_schedule 表，只是 key 用「2026-10」这种月份格式 —— 与周 key（2026-10-08）天然不冲突，
     * 所以按 BOSS 的车次查询、复制逻辑都不用改。
     */
    @Transactional
    public WeekDetail monthTriples() {
        YearMonth thisMonth = YearMonth.now();
        WeekSchedule thisOne = ensureMonth(thisMonth, "本月");
        WeekSchedule lastOne = ensureMonth(thisMonth.minusMonths(1), "上月");
        WeekSchedule nextOne = ensureMonth(thisMonth.plusMonths(1), "下月");
        return new WeekDetail(toView(lastOne), toView(thisOne), toView(nextOne), thisOne.getWeekKey());
    }

    /** 月份周期：统一走 ensureWeek（它已支持月 key），避免维护两套创建逻辑。 */
    private WeekSchedule ensureMonth(YearMonth month, String note) {
        return ensureWeek(monthKeyOf(month), note);
    }

    /** 月份周期的 key：2026-10 */
    public static String monthKeyOf(YearMonth month) {
        return String.format("%04d-%02d", month.getYear(), month.getMonthValue());
    }

    @Transactional
    public WeekDetail triples() {
        LocalDate thisStart = weekStartOf(LocalDate.now());
        WeekSchedule thisWeek = ensureWeek(thisWeekKeyOf(thisStart), "本周");
        WeekSchedule lastWeek = ensureWeek(keyOf(thisStart.minusWeeks(1)), "上周");
        WeekSchedule nextWeek = ensureWeek(keyOf(thisStart.plusWeeks(1)), "下周");
        return new WeekDetail(toView(lastWeek), toView(thisWeek), toView(nextWeek), thisWeek.getWeekKey());
    }

    private static String thisWeekKeyOf(LocalDate start) {
        return start.format(KEY_FORMAT);
    }

    @Transactional(readOnly = true)
    public WeekView getWeek(String weekKey) {
        WeekSchedule week = weekRepository.findById(weekKey)
                .orElseThrow(() -> new NotFoundException("排班周期不存在：" + weekKey));
        return toView(week);
    }

    private WeekView toView(WeekSchedule week) {
        return new WeekView(
                week.getWeekKey(),
                week.getLabel(),
                week.getStartDate(),
                week.getEndDate(),
                week.getNote(),
                runRepository.countByWeekWeekKey(week.getWeekKey()));
    }

    /**
     * 统计某周期里「每个 BOSS 有多少趟车」。
     * 前端排班页的周期标签要显示本 BOSS 的真实趟数，不能拿全周总数糊弄。
     * 返回 weekKey -> (bossId -> 趟数)。
     */
    @Transactional(readOnly = true)
    public Map<String, Map<Long, Long>> runsPerBoss(List<String> weekKeys) {
        Map<String, Map<Long, Long>> result = new LinkedHashMap<>();
        for (String weekKey : weekKeys) {
            Map<Long, Long> counts = new LinkedHashMap<>();
            for (BossRun run : runRepository.findByWeekWeekKeyOrderByIdAsc(weekKey)) {
                counts.merge(run.getBoss().getId(), 1L, Long::sum);
            }
            result.put(weekKey, counts);
        }
        return result;
    }

    /**
     * 按单个 BOSS 复制：把某个 BOSS 在源周期的车次与阵容复制到目标周期。
     * 目标周期里这个 BOSS 已有车次时，默认不覆盖。
     */
    @Transactional
    public WeekView copyBossRuns(String fromKey, String toKey, Long bossId, String tier, boolean overwrite) {
        if (fromKey.equals(toKey)) {
            throw new BusinessException("不能把周期复制到它自己");
        }
        if (!weekRepository.existsById(fromKey)) {
            throw new NotFoundException("源周期不存在：" + fromKey);
        }
        WeekSchedule to = ensureWeek(toKey, null);
        Boss boss = bossRepository.findById(bossId)
                .orElseThrow(() -> new NotFoundException("BOSS 不存在：" + bossId));

        BossTier tierFilter = parseTierOrNull(tier);

        // 融合车：7 个成员 BOSS 各有自己的车次记录，必须逐个复制。
        // 否则只复制到主 BOSS，其余 6 个为空，页面上就会「有的 BOSS 有这趟、有的没有」。
        if (boss.getComboKey() != null) {
            return copyComboRuns(fromKey, toKey, to, boss, tierFilter, overwrite);
        }
        List<BossRun> sources = runRepository.findByWeekWeekKeyOrderByBossSortOrderAscRunIndexAsc(fromKey).stream()
                .filter(run -> run.getBoss().getId().equals(bossId))
                .filter(run -> tierFilter == null || run.getTier() == tierFilter)
                .toList();
        if (sources.isEmpty()) {
            throw new BusinessException("「" + boss.getName() + "」"
                    + (tierFilter == null ? "" : tierFilter.label() + " 难度")
                    + "在源周期没有车次，没东西可复制");
        }

        List<BossRun> existing = runRepository.findByWeekWeekKeyOrderByBossSortOrderAscRunIndexAsc(toKey).stream()
                .filter(run -> run.getBoss().getId().equals(bossId))
                .filter(run -> tierFilter == null || run.getTier() == tierFilter)
                .toList();
        if (!existing.isEmpty()) {
            if (!overwrite) {
                throw new BusinessException("「" + boss.getName() + "」在目标周期已经有 " + existing.size()
                        + " 趟车次了，覆盖请先确认");
            }
            memberRepository.deleteByRunIn(existing);
            runRepository.deleteAll(existing);
            runRepository.flush();
        }

        int[] copied = copyRunsOfBoss(sources, to, boss, tierFilter);
        return new WeekView(
                to.getWeekKey(),
                to.getLabel(),
                to.getStartDate(),
                to.getEndDate(),
                "「" + boss.getName() + "」从「" + fromKey + "」复制了 " + copied[0] + " 趟 / " + copied[1] + " 个席位",
                copied[0]);
    }

    /**
     * 一键复制：把源周期「有排人」的车次与席位复制到目标周期，之后管理员再自行修改。
     * 目标周期已有车次时默认不覆盖，避免误操作；确实要覆盖传 overwrite=true。
     */
    @Transactional
    public WeekView copyWeek(String fromKey, String toKey, boolean overwrite) {
        if (fromKey.equals(toKey)) {
            throw new BusinessException("不能把周期复制到它自己");
        }
        WeekSchedule from = weekRepository.findById(fromKey)
                .orElseThrow(() -> new NotFoundException("源周期不存在：" + fromKey));
        WeekSchedule to = ensureWeek(toKey, null);

        long existingCount = runRepository.countByWeekWeekKey(toKey);
        if (existingCount > 0) {
            if (!overwrite) {
                throw new BusinessException("目标周期（" + to.getLabel() + "）已经有 " + existingCount
                        + " 趟车次了，复制会覆盖它们");
            }
            List<BossRun> existing = runRepository.findByWeekWeekKeyOrderByBossSortOrderAscRunIndexAsc(toKey);
            memberRepository.deleteByRunIn(existing);
            runRepository.deleteAll(existing);
            runRepository.flush();
        }

        List<BossRun> sources = runRepository.findByWeekWeekKeyOrderByBossSortOrderAscRunIndexAsc(fromKey);
        int copiedRuns = 0;
        int copiedMembers = 0;
        for (BossRun source : sources) {
            int[] result = copyRunsOfBoss(List.of(source), to, source.getBoss(), null);
            copiedRuns += result[0];
            copiedMembers += result[1];
        }
        return new WeekView(
                to.getWeekKey(),
                to.getLabel(),
                to.getStartDate(),
                to.getEndDate(),
                "从「" + from.getLabel() + "」复制了 " + copiedRuns + " 趟车次 / " + copiedMembers + " 个席位",
                copiedRuns);
    }
    /** 解析难度，非法或空返回 null。 */
    private static BossTier parseTierOrNull(String tier) {
        if (tier == null || tier.isBlank()) {
            return null;
        }
        try {
            return BossTier.valueOf(tier.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("未知难度：" + tier);
        }
    }

    /** 复制一批车次与阵容到目标周期，返回 {车次数, 席位数}。 */
    /**
     * 复制整个融合车（全部成员 BOSS）。
     *
     * 融合车的每一趟在 7 个成员 BOSS 上各有一条记录，阵容相同。
     * 所以复制时对每个成员 BOSS 都执行一次单 BOSS 复制，保证 7 份记录一致。
     */
    private WeekView copyComboRuns(
            String fromKey,
            String toKey,
            WeekSchedule to,
            Boss clicked,
            BossTier tierFilter,
            boolean overwrite) {
        BossCombo.Combo combo = BossCombo.byKey(clicked.getComboKey());
        List<String> names = combo == null ? List.of(clicked.getName()) : combo.bossNames();
        List<Boss> members = bossRepository.findAllByOrderByGroupSortOrderAscSortOrderAscIdAsc().stream()
                .filter(b -> b.getComboKey() != null && b.getComboKey().equals(clicked.getComboKey()))
                .toList();

        int totalRuns = 0;
        int totalMembers = 0;
        int touched = 0;
        for (Boss member : members) {
            List<BossRun> sources = runRepository
                    .findByWeekWeekKeyOrderByBossSortOrderAscRunIndexAsc(fromKey).stream()
                    .filter(run -> run.getBoss().getId().equals(member.getId()))
                    .filter(run -> tierFilter == null || run.getTier() == tierFilter)
                    .toList();
            List<BossRun> existing = runRepository
                    .findByWeekWeekKeyOrderByBossSortOrderAscRunIndexAsc(toKey).stream()
                    .filter(run -> run.getBoss().getId().equals(member.getId()))
                    .filter(run -> tierFilter == null || run.getTier() == tierFilter)
                    .toList();

            // 目标已存在时：有覆盖就清掉重来，没覆盖就跳过这个成员（不阻断其它成员）
            if (!existing.isEmpty()) {
                if (!overwrite) {
                    continue;
                }
                memberRepository.deleteByRunIn(existing);
                runRepository.deleteAll(existing);
                runRepository.flush();
            }
            if (sources.isEmpty()) {
                continue;
            }
            int[] copied = copyRunsOfBoss(sources, to, member, tierFilter);
            totalRuns += copied[0];
            totalMembers += copied[1];
            touched++;
        }

        String desc = "融合车（" + String.join(" / ", names) + "）从「" + fromKey + "」复制了 "
                + totalRuns + " 趟 / " + totalMembers + " 个席位（覆盖 " + touched + " 个 BOSS）";
        return new WeekView(
                to.getWeekKey(),
                to.getLabel(),
                to.getStartDate(),
                to.getEndDate(),
                desc,
                totalRuns);
    }

    private int[] copyRunsOfBoss(List<BossRun> sources, WeekSchedule target, Boss boss, BossTier forceTier) {
        int runs = 0;
        int members = 0;
        for (BossRun source : sources) {
            List<RunMember> sourceMembers = memberRepository.findByRunIdOrderBySlotNoAscIdAsc(source.getId());
            if (sourceMembers.isEmpty()) {
                continue; // 空车不复制
            }
            BossRun copy = new BossRun();
            copy.setWeek(target);
            copy.setBoss(boss);
            copy.setTier(forceTier != null ? forceTier : source.getTier());
            copy.setRunIndex(source.getRunIndex());
            copy.setScheduled(source.getScheduled());
            copy.setStatus(BossRun.Status.PLANNED);
            copy.setNote(source.getNote());
            runRepository.save(copy);

            Set<Long> used = new java.util.HashSet<>();
            int slot = 0;
            for (RunMember member : sourceMembers) {
                if (!used.add(member.getCharacter().getId())) {
                    continue;
                }
                RunMember newMember = new RunMember();
                newMember.setRun(copy);
                newMember.setCharacter(member.getCharacter());
                newMember.setSlotNo(++slot);
                newMember.setRole(member.getRole());
                newMember.setStatus(member.getStatus());
                newMember.setNote(member.getNote());
                memberRepository.save(newMember);
                members++;
            }
            runs++;
        }
        runRepository.flush();
        return new int[] {runs, members};
    }
}
