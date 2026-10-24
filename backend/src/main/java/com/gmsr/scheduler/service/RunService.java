package com.gmsr.scheduler.service;

import com.gmsr.scheduler.domain.Boss;
import com.gmsr.scheduler.domain.BossRun;
import com.gmsr.scheduler.domain.BossTier;
import com.gmsr.scheduler.domain.GameCharacter;
import com.gmsr.scheduler.domain.RunMember;
import com.gmsr.scheduler.dto.RunDtos.MemberRequest;
import com.gmsr.scheduler.dto.RunDtos.MemberUpdateRequest;
import com.gmsr.scheduler.dto.RunDtos.MemberView;
import com.gmsr.scheduler.dto.RunDtos.PlayerRunView;
import com.gmsr.scheduler.dto.RunDtos.RunRequest;
import com.gmsr.scheduler.dto.RunDtos.RunView;
import com.gmsr.scheduler.repository.BossRepository;
import com.gmsr.scheduler.repository.BossRunRepository;
import com.gmsr.scheduler.repository.GameCharacterRepository;
import com.gmsr.scheduler.repository.RunMemberRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 车次与席位编排（按排班周期组织）。 */
@Service
public class RunService {

    private final BossRunRepository runRepository;
    private final RunMemberRepository memberRepository;
    private final BossRepository bossRepository;
    private final GameCharacterRepository characterRepository;

    public RunService(
            BossRunRepository runRepository,
            RunMemberRepository memberRepository,
            BossRepository bossRepository,
            GameCharacterRepository characterRepository) {
        this.runRepository = runRepository;
        this.memberRepository = memberRepository;
        this.bossRepository = bossRepository;
        this.characterRepository = characterRepository;
    }

    /** 全部车次。 */
    @Transactional(readOnly = true)
    public List<RunView> list() {
        return assemble(runRepository.findAllByOrderByBossSortOrderAscRunIndexAsc());
    }

    @Transactional(readOnly = true)
    public RunView get(Long id) {
        BossRun run = runRepository.findById(id).orElseThrow(() -> new NotFoundException("车次不存在：" + id));
        return assemble(List.of(run)).getFirst();
    }

    /** 同 BOSS、同难度下的下一个趟次序号。 */
    private int nextRunIndex(Boss boss, BossTier tier) {
        return runRepository.findByBossIdOrderByRunIndexAsc(boss.getId()).stream()
                .filter(run -> run.getTier() == tier)
                .mapToInt(BossRun::getRunIndex)
                .max()
                .orElse(0) + 1;
    }

    @Transactional
    public RunView create(RunRequest request) {
        Boss boss = bossRepository.findById(request.bossId())
                .orElseThrow(() -> new NotFoundException("BOSS 不存在：" + request.bossId()));
        BossRun run = new BossRun();
        run.setBoss(boss);
        run.setTier(requireAllowedTier(boss, request.tier() == null ? boss.getTier().name() : request.tier()));
        run.setRunIndex(request.runIndex() == null
                ? nextRunIndex(boss, run.getTier())
                : request.runIndex());
        run.setScheduled(request.scheduled());
        run.setStatus(request.status() == null ? BossRun.Status.PLANNED : BossRun.Status.valueOf(request.status()));
        run.setNote(request.note());
        runRepository.save(run);
        return get(run.getId());
    }

    @Transactional
    public RunView update(Long id, RunRequest request) {
        BossRun run = runRepository.findById(id).orElseThrow(() -> new NotFoundException("车次不存在：" + id));
        if (request.bossId() != null && !request.bossId().equals(run.getBoss().getId())) {
            Boss boss = bossRepository.findById(request.bossId())
                    .orElseThrow(() -> new NotFoundException("BOSS 不存在：" + request.bossId()));
            run.setBoss(boss);
        }
        if (request.tier() != null) {
            run.setTier(requireAllowedTier(run.getBoss(), request.tier()));
        }
        if (request.runIndex() != null) {
            run.setRunIndex(request.runIndex());
        }
        run.setScheduled(request.scheduled());
        if (request.status() != null) {
            run.setStatus(BossRun.Status.valueOf(request.status()));
        }
        run.setNote(request.note());
        runRepository.save(run);
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        BossRun run = runRepository.findById(id).orElseThrow(() -> new NotFoundException("车次不存在：" + id));
        // 先删席位再删车次：run_member 有外键约束，直接删车次会失败
        memberRepository.deleteAll(memberRepository.findByRunIdOrderBySlotNoAscIdAsc(id));
        memberRepository.flush();
        runRepository.delete(run);
    }

    /**
     * 把某个 BOSS 的车次重新编号。
     * 编号按「难度」分组各自从 1 开始（Normal 第1趟、第2趟…；Chaos 第1趟、第2趟…），
     * 因为排班页是按难度分开显示的。
     */
    @Transactional
    public List<RunView> resequence(Long bossId) {
        List<BossRun> runs = runRepository.findByBossIdOrderByRunIndexAsc(bossId).stream()
                .sorted(java.util.Comparator
                        .comparing((BossRun run) -> run.getTier().name())
                        .thenComparing(BossRun::getRunIndex)
                        .thenComparing(BossRun::getId))
                .toList();
        Map<String, Integer> counters = new java.util.LinkedHashMap<>();
        for (BossRun run : runs) {
            int next = counters.merge(run.getTier().name(), 1, Integer::sum);
            run.setRunIndex(next);
        }
        runRepository.saveAll(runs);
        return assemble(runs);
    }

    @Transactional
    public RunView addMember(Long runId, MemberRequest request) {
        BossRun run = runRepository.findById(runId).orElseThrow(() -> new NotFoundException("车次不存在：" + runId));
        GameCharacter character = characterRepository.findById(request.characterId())
                .orElseThrow(() -> new NotFoundException("角色不存在：" + request.characterId()));
        memberRepository.findByRunIdAndCharacterId(runId, character.getId()).ifPresent(existing -> {
            throw new BusinessException(
                    character.getPlayer().getNickname() + " 的 " + character.getJob() + " 已经在这趟车上了");
        });
        // 同一趟车不能出现同一个群友的两个角色
        ensureSingleCharacterPerPlayer(runId, character.getId(), null);
        long current = memberRepository.countByRunId(runId);
        int capacity = run.getBoss().getCapacity();
        if (current >= capacity) {
            throw new BusinessException("这趟车已满 " + capacity + " 人，请先移除成员或调整 BOSS 人数上限");
        }
        RunMember member = new RunMember();
        member.setRun(run);
        member.setCharacter(character);
        member.setSlotNo(request.slotNo() == null ? (int) current + 1 : request.slotNo());
        member.setRole(request.role() == null ? RunMember.Role.MEMBER : RunMember.Role.valueOf(request.role()));
        member.setStatus(request.status() == null
                ? RunMember.Status.CONFIRMED
                : RunMember.Status.valueOf(request.status()));
        member.setNote(request.note());
        memberRepository.save(member);
        return get(runId);
    }

    @Transactional
    public RunView updateMember(Long memberId, MemberUpdateRequest request) {
        RunMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new NotFoundException("席位不存在：" + memberId));
        if (request.slotNo() != null) {
            member.setSlotNo(request.slotNo());
        }
        if (request.role() != null) {
            member.setRole(RunMember.Role.valueOf(request.role()));
        }
        if (request.status() != null) {
            member.setStatus(RunMember.Status.valueOf(request.status()));
        }
        member.setNote(request.note());
        memberRepository.save(member);
        return get(member.getRun().getId());
    }

    @Transactional
    public RunView removeMember(Long memberId) {
        RunMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new NotFoundException("席位不存在：" + memberId));
        Long runId = member.getRun().getId();
        memberRepository.delete(member);
        return get(runId);
    }

    /** 有编辑权限的人（管理员/编辑）或这趟车上的群友本人，都算可以动这趟车。 */
    @Transactional(readOnly = true)
    public boolean canTouch(Long runId, boolean editor, Long playerId) {
        if (editor) {
            return true;
        }
        if (playerId == null) {
            return false;
        }
        return memberRepository.findByRunIdOrderBySlotNoAscIdAsc(runId).stream()
                .anyMatch(m -> m.getCharacter().getPlayer().getId().equals(playerId));
    }

    /** 群友标记自己所在车次的攻坚状态。 */
    @Transactional
    public RunView updateStatus(Long runId, String statusStr) {
        BossRun run = runRepository.findById(runId).orElseThrow(() -> new NotFoundException("车次不存在：" + runId));
        BossRun.Status status;
        try {
            status = BossRun.Status.valueOf(statusStr == null ? "" : statusStr.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("未知状态：" + statusStr);
        }
        run.setStatus(status);
        runRepository.save(run);
        return get(runId);
    }

    /**
     * 拖拽调车：把某个席位（角色）从它当前所在的车次挪到目标车次。
     * 支持同 BOSS 换车，也支持跨 BOSS 调车；目标车次满员或该角色已在车上会被拒绝。
     * 返回 { 源车次视图, 目标车次视图 }，前端据此刷新两趟车。
     */
    @Transactional
    public Map<String, RunView> moveMember(Long memberId, Long targetRunId, Integer slotNo) {
        RunMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new NotFoundException("席位不存在：" + memberId));
        BossRun source = member.getRun();
        if (source.getId().equals(targetRunId)) {
            throw new BusinessException("已经在这趟车上了，不用拖动");
        }
        BossRun target = runRepository.findById(targetRunId)
                .orElseThrow(() -> new NotFoundException("目标车次不存在：" + targetRunId));

        GameCharacter character = member.getCharacter();
        memberRepository.findByRunIdAndCharacterId(target.getId(), character.getId()).ifPresent(existing -> {
            throw new BusinessException(character.getPlayer().getNickname() + " 的 " + character.getJob()
                    + " 已经在目标车次上了");
        });
        // 目标车上已经有这个群友的别的角色 → 不允许（同一趟车只能上一个自己的角色）
        ensureSingleCharacterPerPlayer(target.getId(), character.getId(), null);
        long targetCount = memberRepository.countByRunId(target.getId());
        int capacity = target.getBoss().getCapacity();
        if (targetCount >= capacity) {
            throw new BusinessException("目标车次已满 " + capacity + " 人，调不过去");
        }

        member.setRun(target);
        member.setSlotNo(slotNo == null ? (int) targetCount + 1 : slotNo);
        memberRepository.save(member);
        memberRepository.flush();

        Map<String, RunView> result = new LinkedHashMap<>();
        result.put("source", get(source.getId()));
        result.put("target", get(target.getId()));
        return result;
    }

    /**
     * 交换两个席位（换位）。
     * 场景：同一个 BOSS 下，A 车有我的阴阳师、B 车有我的剑豪 —— 这两个角色不可能同时上同一个 BOSS，
     * 所以把其中一个拖到另一个身上时，正确语义是「两人对调」，而不是「塞进 B 车」。
     * 两个席位分别在两趟车上，交换后两边人数不变，因此不受容量限制。
     * 返回两趟车的最新数据。
     */
    @Transactional
    public Map<String, RunView> swapMembers(Long memberId, Long otherMemberId) {
        if (memberId.equals(otherMemberId)) {
            throw new BusinessException("不能和自己换位");
        }
        RunMember a = memberRepository.findById(memberId)
                .orElseThrow(() -> new NotFoundException("席位不存在：" + memberId));
        RunMember b = memberRepository.findById(otherMemberId)
                .orElseThrow(() -> new NotFoundException("席位不存在：" + otherMemberId));
        BossRun runA = a.getRun();
        BossRun runB = b.getRun();
        if (runA.getId().equals(runB.getId())) {
            throw new BusinessException("两个角色已经在同一趟车上，不用换位");
        }
        // 必须是同一个 BOSS 的车次才允许换位（不同 BOSS 之间没有「不能同时上」的约束）
        if (!runA.getBoss().getId().equals(runB.getBoss().getId())) {
            throw new BusinessException("只能在同一 BOSS 的车次之间换位");
        }
        // 换位后：a 的角色进 runB、b 的角色进 runA。
        // 要确保两边都不会出现「同一个群友的两个角色」。
        // 允许保留的席位就是即将从该车换出去的那个角色。
        ensureSingleCharacterPerPlayer(runB.getId(), a.getCharacter().getId(), b.getCharacter().getId());
        ensureSingleCharacterPerPlayer(runA.getId(), b.getCharacter().getId(), a.getCharacter().getId());
        int slotA = a.getSlotNo();
        int slotB = b.getSlotNo();
        a.setRun(runB);
        a.setSlotNo(slotB);
        b.setRun(runA);
        b.setSlotNo(slotA);
        memberRepository.save(a);
        memberRepository.save(b);
        memberRepository.flush();

        Map<String, RunView> result = new LinkedHashMap<>();
        result.put("source", get(runA.getId()));
        result.put("target", get(runB.getId()));
        return result;
    }

    /**
     * 校验「同一趟车只能有一个自己的角色」。
     * 规则来源：同一个 BOSS 你不可能用两个角色同时打，所以同一车次里
     * 同一个群友（玩家）只能占一个席位 —— 哪怕角色不同也不行。
     *
     * @param allowCharacterId 允许保留的席位（换位时对方那个正在换出去的席位），可为 null
     */
    private void ensureSingleCharacterPerPlayer(Long runId, Long characterId, Long allowCharacterId) {
        if (runId == null || characterId == null) {
            return;
        }
        GameCharacter incoming = characterRepository.findById(characterId)
                .orElseThrow(() -> new NotFoundException("角色不存在：" + characterId));
        if (incoming.getPlayer() == null) {
            return;
        }
        Long playerId = incoming.getPlayer().getId();
        memberRepository.findByRunIdAndCharacterPlayerId(runId, playerId).stream()
                .filter(m -> allowCharacterId == null || !allowCharacterId.equals(m.getCharacter().getId()))
                .findFirst()
                .ifPresent(existing -> {
                    throw new BusinessException("「" + incoming.getPlayer().getNickname() + "」已经在这趟车上了（"
                            + existing.getCharacter().getJob() + "）；同一个 BOSS 不能用两个角色同时打，"
                            + "同一个人同一趟车只能上一个角色");
                });
    }

    /**
     * 校验难度是否属于该 BOSS 实际存在的难度，避免排出游戏里根本没有的难度。
     */
    private static BossTier requireAllowedTier(Boss boss, String tier) {
        BossTier parsed = BossService.parseTier(tier);
        List<String> allowed = BossService.tiersOf(boss);
        if (!allowed.isEmpty() && parsed != BossTier.NONE && !allowed.contains(parsed.name())) {
            throw new BusinessException("「" + boss.getName() + "」没有 "
                    + parsed.label() + " 难度，可选：" + String.join(" / ",
                    allowed.stream().map(name -> BossTier.valueOf(name).label()).toList()));
        }
        return parsed;
    }

    /** 这趟车我上了哪些角色。 */
    @Transactional(readOnly = true)
    public List<MemberView> myMembers(Long runId, Long playerId) {
        if (playerId == null) {
            return List.of();
        }
        return memberRepository.findByRunIdOrderBySlotNoAscIdAsc(runId).stream()
                .filter(m -> playerId.equals(m.getCharacter().getPlayer().getId()))
                .map(RunService::toMemberView)
                .toList();
    }

    /** 整队保存：用给定角色列表替换这趟车的全部席位。 */
    @Transactional
    public RunView replaceMembers(Long runId, List<MemberRequest> requests) {
        BossRun run = runRepository.findById(runId).orElseThrow(() -> new NotFoundException("车次不存在：" + runId));
        int capacity = run.getBoss().getCapacity();
        if (requests != null && requests.size() > capacity) {
            throw new BusinessException("这趟车最多 " + capacity + " 人，当前提交 " + requests.size() + " 人");
        }
        // 整队保存：同一批里不能出现同一个群友的两个角色
        if (requests != null) {
            Set<Long> seenPlayers = new LinkedHashSet<>();
            for (MemberRequest request : requests) {
                if (request == null || request.characterId() == null) {
                    continue;
                }
                GameCharacter character = characterRepository.findById(request.characterId()).orElse(null);
                if (character == null || character.getPlayer() == null) {
                    continue;
                }
                if (!seenPlayers.add(character.getPlayer().getId())) {
                    throw new BusinessException("「" + character.getPlayer().getNickname()
                            + "」在这趟车里出现了两个角色；同一趟车只能上一个自己的角色");
                }
            }
        }
        memberRepository.deleteAll(memberRepository.findByRunIdOrderBySlotNoAscIdAsc(runId));
        if (requests != null) {
            Set<Long> seen = new LinkedHashSet<>();
            int slot = 0;
            for (MemberRequest request : requests) {
                if (request == null || request.characterId() == null || !seen.add(request.characterId())) {
                    continue;
                }
                GameCharacter character = characterRepository.findById(request.characterId())
                        .orElseThrow(() -> new NotFoundException("角色不存在：" + request.characterId()));
                RunMember member = new RunMember();
                member.setRun(run);
                member.setCharacter(character);
                member.setSlotNo(++slot);
                member.setRole(request.role() == null ? RunMember.Role.MEMBER : RunMember.Role.valueOf(request.role()));
                member.setStatus(request.status() == null
                        ? RunMember.Status.CONFIRMED
                        : RunMember.Status.valueOf(request.status()));
                member.setNote(request.note());
                memberRepository.save(member);
            }
        }
        return get(runId);
    }

    /** 某个群友（账号）在哪些车次上。 */
    @Transactional(readOnly = true)
    public List<PlayerRunView> playerRuns(Long playerId) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Object[] row : memberRepository.countGroupedByRun()) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        List<RunMember> memberships = memberRepository.findByCharacterPlayerIdOrderByIdAsc(playerId);
        return memberships.stream()
                .map(m -> new PlayerRunView(
                        m.getRun().getId(),
                        m.getRun().getBoss().getId(),
                        m.getRun().getBoss().getName(),
                        m.getRun().getBoss().getGroup().getName(),
                        m.getRun().getTier().name(),
                        m.getRun().getRunIndex(),
                        m.getRun().getScheduled(),
                        m.getRun().getStatus().name(),
                        counts.getOrDefault(m.getRun().getId(), 0),
                        m.getRun().getBoss().getCapacity(),
                        m.getCharacter().getId(),
                        m.getCharacter().getJob(),
                        m.getCharacter().getPower()))
                .toList();
    }

    /** 批量组装车次视图，避免 N+1 查询。 */
    private List<RunView> assemble(List<BossRun> runs) {
        if (runs.isEmpty()) {
            return List.of();
        }
        List<Long> runIds = runs.stream().map(BossRun::getId).toList();
        Map<Long, List<RunMember>> membersByRun = memberRepository.findByRunIds(runIds).stream()
                .collect(Collectors.groupingBy(m -> m.getRun().getId(), LinkedHashMap::new, Collectors.toList()));
        List<RunView> result = new ArrayList<>(runs.size());
        for (BossRun run : runs) {
            List<MemberView> members = membersByRun.getOrDefault(run.getId(), List.of()).stream()
                    .sorted(Comparator.comparingInt(RunMember::getSlotNo).thenComparing(RunMember::getId))
                    .map(RunService::toMemberView)
                    .toList();
            int powerTotal = members.stream().mapToInt(MemberView::power).sum();
            result.add(new RunView(
                    run.getId(),
                    run.getBoss().getId(),
                    run.getBoss().getName(),
                    run.getBoss().getIconKey(),
                    run.getBoss().getGroup().getName(),
                    run.getTier().name(),
                    run.getTier().label(),
                    run.getBoss().getCapacity(),
                    run.getRunIndex(),
                    run.getScheduled(),
                    run.getStatus().name(),
                    run.getNote(),
                    members.size(),
                    powerTotal,
                    members));
        }
        return result;
    }

    private static MemberView toMemberView(RunMember member) {
        GameCharacter character = member.getCharacter();
        return new MemberView(
                member.getId(),
                character.getId(),
                character.getPlayer().getId(),
                character.getPlayer().getNickname(),
                character.getJob(),
                character.getPower(),
                member.getSlotNo(),
                member.getRole().name(),
                member.getStatus().name(),
                member.getNote(),
                character.getNote(),
                character.isMainChar());
    }
}
