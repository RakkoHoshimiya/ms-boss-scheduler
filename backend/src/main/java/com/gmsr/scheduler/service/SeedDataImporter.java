package com.gmsr.scheduler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gmsr.scheduler.domain.Boss;
import com.gmsr.scheduler.domain.BossGroup;
import com.gmsr.scheduler.domain.BossRun;
import com.gmsr.scheduler.domain.BossTier;
import com.gmsr.scheduler.domain.GameCharacter;
import com.gmsr.scheduler.domain.Player;
import com.gmsr.scheduler.domain.RunMember;
import com.gmsr.scheduler.repository.BossGroupRepository;
import com.gmsr.scheduler.repository.BossRepository;
import com.gmsr.scheduler.repository.BossRunRepository;
import com.gmsr.scheduler.repository.GameCharacterRepository;
import com.gmsr.scheduler.repository.PlayerRepository;
import com.gmsr.scheduler.repository.RunMemberRepository;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首次启动时把 Excel 排班表整理出的种子数据导入数据库。
 * 仅当 group / player / run 三张表都为空时执行，避免覆盖手工维护的数据。
 */
@Component
@Order(1)
public class SeedDataImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataImporter.class);

    private final BossGroupRepository groupRepository;
    private final BossRepository bossRepository;
    private final PlayerRepository playerRepository;
    private final GameCharacterRepository characterRepository;
    private final BossRunRepository runRepository;
    private final RunMemberRepository memberRepository;
    private final ObjectMapper objectMapper;

    public SeedDataImporter(
            BossGroupRepository groupRepository,
            BossRepository bossRepository,
            PlayerRepository playerRepository,
            GameCharacterRepository characterRepository,
            BossRunRepository runRepository,
            RunMemberRepository memberRepository,
            ObjectMapper objectMapper) {
        this.groupRepository = groupRepository;
        this.bossRepository = bossRepository;
        this.playerRepository = playerRepository;
        this.characterRepository = characterRepository;
        this.runRepository = runRepository;
        this.memberRepository = memberRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        boolean empty = runRepository.count() == 0 && playerRepository.count() == 0;
        if (!empty) {
            log.info("检测到已有业务数据，跳过种子数据导入");
            return;
        }
        ClassPathResource resource = new ClassPathResource("seed/seed-data.json");
        if (!resource.exists()) {
            log.warn("未找到 seed/seed-data.json，跳过种子数据导入");
            return;
        }
        SeedData seed;
        try (InputStream in = resource.getInputStream()) {
            seed = objectMapper.readValue(in, SeedData.class);
        }
        importPlayers(seed);
        importRuns(seed);
        log.info("种子数据导入完成：群友 {} 人，角色 {} 个，车次 {} 趟",
                playerRepository.count(), characterRepository.count(), runRepository.count());
    }

    private void importPlayers(SeedData seed) {
        int playerSort = 1;
        for (SeedPlayer seedPlayer : safe(seed.players())) {
            Player player = playerRepository.findByNickname(seedPlayer.nickname()).orElseGet(() -> {
                Player created = new Player();
                created.setNickname(seedPlayer.nickname());
                created.setRemark(seedPlayer.remark());
                created.setActive(true);
                created.setSortOrder(playerSort);
                return playerRepository.save(created);
            });
            int charSort = 1;
            for (SeedCharacter seedCharacter : safe(seedPlayer.characters())) {
                GameCharacter character = new GameCharacter();
                character.setPlayer(player);
                character.setJob(seedCharacter.job());
                character.setPower(seedCharacter.power());
                character.setMainChar(seedCharacter.mainChar());
                character.setSortOrder(charSort++);
                characterRepository.save(character);
            }
        }
    }

    private void importRuns(SeedData seed) {
        Map<String, BossGroup> groups = new LinkedHashMap<>();
        for (BossGroup group : groupRepository.findAllByOrderBySortOrderAscIdAsc()) {
            groups.put(group.getName(), group);
        }
        int groupSort = groups.size();
        for (SeedGroup seedGroup : safe(seed.groups())) {
            if (!groups.containsKey(seedGroup.name())) {
                BossGroup group = new BossGroup();
                group.setName(seedGroup.name());
                group.setSortOrder(++groupSort);
                groups.put(group.getName(), groupRepository.save(group));
            }
        }

        List<GameCharacter> allCharacters = characterRepository.findAllByOrderByPlayerSortOrderAscSortOrderAscIdAsc();
        Map<String, Map<String, GameCharacter>> byPlayerAndJob = new LinkedHashMap<>();
        Map<String, List<GameCharacter>> byPlayer = new LinkedHashMap<>();
        for (GameCharacter character : allCharacters) {
            byPlayerAndJob
                    .computeIfAbsent(character.getPlayer().getNickname(), k -> new LinkedHashMap<>())
                    .putIfAbsent(character.getJob(), character);
            byPlayer
                    .computeIfAbsent(character.getPlayer().getNickname(), k -> new ArrayList<>())
                    .add(character);
        }

        for (SeedGroup seedGroup : safe(seed.groups())) {
            BossGroup group = groups.get(seedGroup.name());
            int bossSort = 0;
            for (SeedBoss seedBoss : safe(seedGroup.bosses())) {
                Boss boss = new Boss();
                boss.setGroup(group);
                boss.setName(seedBoss.name());
                boss.setTier(parseTier(seedBoss.tier()));
                boss.setCapacity(seedBoss.capacity() == null ? 6 : seedBoss.capacity());
                boss.setSortOrder(++bossSort);
                boss.setEnabled(true);
                bossRepository.save(boss);

                int runIndex = 0;
                for (SeedRun seedRun : safe(seedBoss.runs())) {
                    BossRun run = new BossRun();
                    run.setBoss(boss);
                    run.setTier(parseTier(seedRun.tier() == null ? seedBoss.tier() : seedRun.tier()));
                    run.setRunIndex(++runIndex);
                    run.setScheduled(seedRun.scheduled());
                    run.setStatus(BossRun.Status.PLANNED);
                    run.setNote(seedRun.note());
                    runRepository.save(run);

                    int slot = 0;
                    for (SeedMember seedMember : safe(seedRun.members())) {
                        GameCharacter character = resolveCharacter(byPlayerAndJob, byPlayer, seedMember);
                        if (character == null) {
                            log.warn("种子数据找不到角色，已跳过：{} {} {}",
                                    seedMember.player(), seedMember.job(), seedMember.power());
                            continue;
                        }
                        RunMember member = new RunMember();
                        member.setRun(run);
                        member.setCharacter(character);
                        member.setSlotNo(++slot);
                        member.setRole(RunMember.Role.MEMBER);
                        member.setStatus(RunMember.Status.CONFIRMED);
                        memberRepository.save(member);
                    }
                }
            }
        }
    }

    /** 优先按「昵称 + 职业」匹配，再退化为「昵称 + 战力」匹配。 */
    private GameCharacter resolveCharacter(
            Map<String, Map<String, GameCharacter>> byPlayerAndJob,
            Map<String, List<GameCharacter>> byPlayer,
            SeedMember seedMember) {
        Map<String, GameCharacter> byJob = byPlayerAndJob.get(seedMember.player());
        if (byJob == null) {
            return null;
        }
        GameCharacter exact = byJob.get(seedMember.job());
        if (exact != null) {
            return exact;
        }
        return byPlayer.getOrDefault(seedMember.player(), List.of()).stream()
                .filter(c -> seedMember.power() != null && c.getPower() == seedMember.power())
                .findFirst()
                .orElse(null);
    }

    private static BossTier parseTier(String tier) {
        if (tier == null || tier.isBlank()) {
            return BossTier.NONE;
        }
        try {
            return BossTier.valueOf(tier.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return BossTier.NONE;
        }
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    public record SeedData(List<SeedGroup> groups, List<SeedPlayer> players) {
    }

    public record SeedGroup(String name, List<SeedBoss> bosses) {
    }

    public record SeedBoss(String name, String tier, Integer capacity, List<SeedRun> runs) {
    }

    public record SeedRun(String tier, Integer runIndex, String scheduled, String note, List<SeedMember> members) {
    }

    public record SeedMember(String player, String job, Integer power) {
    }

    public record SeedPlayer(String nickname, String remark, List<SeedCharacter> characters) {
    }

    public record SeedCharacter(String job, Integer power, boolean mainChar) {
    }
}
