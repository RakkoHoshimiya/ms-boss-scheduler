package com.gmsr.scheduler.service;

import com.gmsr.scheduler.domain.GameCharacter;
import com.gmsr.scheduler.domain.JobCatalog;
import com.gmsr.scheduler.domain.Player;
import com.gmsr.scheduler.dto.PlayerDtos.CharacterRequest;
import com.gmsr.scheduler.dto.PlayerDtos.MyCharacterRequest;
import com.gmsr.scheduler.dto.PlayerDtos.CharacterView;
import com.gmsr.scheduler.dto.PlayerDtos.PlayerRequest;
import com.gmsr.scheduler.dto.PlayerDtos.PlayerView;
import com.gmsr.scheduler.repository.AppUserRepository;
import com.gmsr.scheduler.repository.GameCharacterRepository;
import com.gmsr.scheduler.repository.PlayerRepository;
import com.gmsr.scheduler.security.CurrentUser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 群友与角色维护。 */
@Service
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final AppUserRepository userRepository;
    private final GameCharacterRepository characterRepository;
    /** 改角色属主时，要检查这个角色所在的每趟车里是否已有新属主的角色 */
    private final com.gmsr.scheduler.repository.RunMemberRepository memberRepository;

    public PlayerService(
            PlayerRepository playerRepository,
            AppUserRepository userRepository,
            GameCharacterRepository characterRepository,
            com.gmsr.scheduler.repository.RunMemberRepository memberRepository) {
        this.playerRepository = playerRepository;
        this.userRepository = userRepository;
        this.characterRepository = characterRepository;
        this.memberRepository = memberRepository;
    }

    /**
     * 群友角色一览表：每位群友的角色按战力从高到低排列，
     * 群友之间也按「自己最强角色的战力」从高到低排序（战力相同按 id 稳定排序）。
     */
    @Transactional(readOnly = true)
    public List<PlayerView> list() {
        List<Player> players = playerRepository.findAllByOrderBySortOrderAscIdAsc();
        List<GameCharacter> characters = characterRepository.findAllByOrderByPlayerSortOrderAscSortOrderAscIdAsc();
        // Group once; scanning every character for every player becomes quadratic.
        var byPlayer = characters.stream().collect(java.util.stream.Collectors.groupingBy(c -> c.getPlayer().getId()));
        boolean canSeeAllQq = CurrentUser.isStaff();
        Long myPlayerId = CurrentUser.playerId();
        List<PlayerView> result = new ArrayList<>(players.size());
        for (Player player : players) {
            result.add(playerView(player, byPlayer.getOrDefault(player.getId(), List.of()), canSeeAllQq, myPlayerId));
        }
        // 最强战力高的排前面
        result.sort(Comparator.comparingInt((PlayerView view) ->
                view.characters().isEmpty() ? 0 : view.characters().getFirst().power()).reversed()
                .thenComparing(PlayerView::id));
        return result;
    }

    private PlayerView playerView(Player player, List<GameCharacter> characters, boolean canSeeAllQq, Long myPlayerId) {
        List<CharacterView> own = characters.stream()
                .sorted(Comparator.comparingInt(GameCharacter::getPower).reversed().thenComparing(GameCharacter::getId))
                .map(PlayerService::toView).toList();
        return new PlayerView(player.getId(), player.getNickname(),
                (canSeeAllQq || player.getId().equals(myPlayerId)) ? player.getQq() : null,
                player.getQq() != null && !player.getQq().isBlank(), player.getRemark(), player.isActive(), player.getSortOrder(), own.size(), own);
    }

    /** 某个群友自己的角色（供群友本人管理用）。 */
    @Transactional(readOnly = true)
    public List<CharacterView> charactersOfPlayer(Long playerId) {
        return characterRepository.findByPlayerIdOrderBySortOrderAscIdAsc(playerId).stream()
                .sorted(Comparator.comparingInt(GameCharacter::getPower).reversed()
                        .thenComparing(GameCharacter::getId))
                .map(PlayerService::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CharacterView> listCharacters() {
        return characterRepository.findAllByOrderByPlayerSortOrderAscSortOrderAscIdAsc().stream()
                .map(PlayerService::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public PlayerView get(Long id) {
        Player player = playerRepository.findById(id).orElseThrow(() -> new NotFoundException("群友不存在：" + id));
        return playerView(player, characterRepository.findByPlayerIdOrderBySortOrderAscIdAsc(id), CurrentUser.isStaff(), CurrentUser.playerId());
    }

    @Transactional
    public PlayerView create(PlayerRequest request) {
        String nickname = request.nickname().trim();
        playerRepository.findByNickname(nickname).ifPresent(existing -> {
            throw new BusinessException("显示名称已存在：" + nickname);
        });
        validateQq(request.qq(), null);
        Player player = new Player();
        player.setNickname(nickname);
        player.setQq(normalizeQq(request.qq()));
        player.setRemark(request.remark());
        player.setActive(request.active() == null || request.active());
        player.setSortOrder(request.sortOrder() == null ? nextPlayerSort() : request.sortOrder());
        playerRepository.save(player);
        return get(player.getId());
    }

    @Transactional
    public PlayerView update(Long id, PlayerRequest request) {
        Player player = playerRepository.findById(id).orElseThrow(() -> new NotFoundException("群友不存在：" + id));
        String nickname = request.nickname().trim();
        playerRepository.findByNickname(nickname).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new BusinessException("群友昵称已存在：" + nickname);
            }
        });
        validateQq(request.qq(), id);   // 以前只有新建时校验，改群友会绕过格式/重复检查
        player.setNickname(nickname);
        player.setQq(normalizeQq(request.qq()));
        player.setRemark(request.remark());
        if (request.active() != null) {
            player.setActive(request.active());
        }
        if (request.sortOrder() != null) {
            player.setSortOrder(request.sortOrder());
        }
        playerRepository.save(player);
        // 群友名就是账号显示名：这里改了，账号列表 / 侧栏也要跟着变
        userRepository.findByPlayerId(id).ifPresent(user -> {
            if (!nickname.equals(user.getDisplayName())) {
                user.setDisplayName(nickname);
                userRepository.save(user);
            }
        });
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        Player player = playerRepository.findById(id).orElseThrow(() -> new NotFoundException("群友不存在：" + id));
        // 外键顺序：席位 → 角色 → 群友。少任何一步都会因为外键失败（以前报的是 500）。
        memberRepository.deleteByPlayerId(id);
        characterRepository.deleteByPlayerId(id);
        playerRepository.delete(player);
    }

    /** 群友本人在「我的角色」页新增角色。 */
    @Transactional
    public CharacterView createOwnCharacter(Long playerId, MyCharacterRequest request) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new NotFoundException("群友不存在：" + playerId));
        long count = characterRepository.countByPlayerId(player.getId());
        if (count >= 12) {
            throw new BusinessException("一个账号最多登记 12 个角色");
        }
        // 与管理员路径一致：归一化职业名 + 同职业唯一，避免群友自助造出重复角色
        String job = JobCatalog.canonical(request.job());
        ensureJobUnique(player, job, null);
        GameCharacter character = new GameCharacter();
        character.setPlayer(player);
        character.setJob(job);
        character.setPower(request.power() == null ? 0 : Math.max(0, request.power()));
        character.setMainChar(Boolean.TRUE.equals(request.mainChar()));
        character.setNote(request.note());
        character.setSortOrder((int) count + 1);
        characterRepository.save(character);
        return toView(character);
    }

    /** 群友本人修改自己的角色。 */
    @Transactional
    public CharacterView updateOwnCharacter(Long playerId, Long characterId, MyCharacterRequest request) {
        GameCharacter character = characterRepository.findById(characterId)
                .orElseThrow(() -> new NotFoundException("角色不存在：" + characterId));
        if (!character.getPlayer().getId().equals(playerId)) {
            throw new ForbiddenException("只能修改自己的角色");
        }
        // 与管理员路径保持一致：职业名归一化 + 同一群友同职业唯一
        // （否则群友自己就能登记出「Kanna + 阴阳师」两个同职业角色）
        String job = JobCatalog.canonical(request.job());
        ensureJobUnique(character.getPlayer(), job, characterId);
        character.setJob(job);
        character.setPower(request.power() == null ? 0 : Math.max(0, request.power()));
        character.setMainChar(Boolean.TRUE.equals(request.mainChar()));
        character.setNote(request.note());
        characterRepository.save(character);
        return toView(character);
    }

    /** 群友本人删除自己的角色。 */
    @Transactional
    public void deleteOwnCharacter(Long playerId, Long characterId) {
        GameCharacter character = characterRepository.findById(characterId)
                .orElseThrow(() -> new NotFoundException("角色不存在：" + characterId));
        if (!character.getPlayer().getId().equals(playerId)) {
            throw new ForbiddenException("只能删除自己的角色");
        }
        memberRepository.deleteByCharacterId(characterId);
        characterRepository.delete(character);
    }

    @Transactional
    public CharacterView createCharacter(CharacterRequest request) {
        Player player = playerRepository.findById(request.playerId())
                .orElseThrow(() -> new NotFoundException("群友不存在：" + request.playerId()));
        ensureJobUnique(player, request.job(), null);
        GameCharacter character = new GameCharacter();
        character.setPlayer(player);
        apply(character, request);
        character.setSortOrder(request.sortOrder() == null
                ? (int) characterRepository.countByPlayerId(player.getId()) + 1
                : request.sortOrder());
        characterRepository.save(character);
        return toView(character);
    }

    @Transactional
    public CharacterView updateCharacter(Long id, CharacterRequest request) {
        GameCharacter character = characterRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("角色不存在：" + id));
        if (!character.getPlayer().getId().equals(request.playerId())) {
            Player player = playerRepository.findById(request.playerId())
                    .orElseThrow(() -> new NotFoundException("群友不存在：" + request.playerId()));
            // 改属主可能让「同一个群友在同一趟车里出现两个角色」，先给出友好提示
            // （数据库触发器 trg_character_owner_change 是兜底）
            for (var member : memberRepository.findByCharacterIdOrderByIdAsc(id)) {
                boolean conflict = memberRepository
                        .findByRunIdAndCharacterPlayerId(member.getRun().getId(), player.getId())
                        .stream()
                        .anyMatch(other -> !other.getId().equals(member.getId()));
                if (conflict) {
                    throw new BusinessException("不能改到「" + player.getNickname()
                            + "」名下：车次「" + member.getRun().getBoss().getName()
                            + "」里已经有他的另一个角色了");
                }
            }
            character.setPlayer(player);
        }
        ensureJobUnique(character.getPlayer(), request.job(), id);
        apply(character, request);
        if (request.sortOrder() != null) {
            character.setSortOrder(request.sortOrder());
        }
        characterRepository.save(character);
        return toView(character);
    }

    /**
     * 同一个群友不能登记两个同职业的角色。
     *
     * 为什么：一个群友在同一趟车里只能上一个角色，同职业登记两条没有排班意义，
     * 而且会让人以为「他有两个 Kanna」——
     * 实际上历史数据里就是这么来的（Kanna 和 阴阳师 被当成两个职业录了两条）。
     *
     * 比较前先折算标准名，所以「Kanna」和「阴阳师」会被认成同一个。
     */
    private void ensureJobUnique(Player player, String job, Long selfId) {
        String target = JobCatalog.canonical(job);
        if (target.isEmpty()) {
            return;
        }
        boolean duplicated = characterRepository.findByPlayerIdOrderBySortOrderAscIdAsc(player.getId()).stream()
                .anyMatch(c -> (selfId == null || !c.getId().equals(selfId))
                        && JobCatalog.sameJob(c.getJob(), target));
        if (duplicated) {
            throw new BusinessException("「" + player.getNickname() + "」已经有 " + target
                    + " 这个角色了（同一职业不能重复登记，阴阳师与 Kanna 视为同一职业）");
        }
    }

    @Transactional
    public void deleteCharacter(Long id) {
        GameCharacter character = characterRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("角色不存在：" + id));
        // 先删它的席位，否则 run_member 的外键会拦住（以前是 500）
        memberRepository.deleteByCharacterId(id);
        characterRepository.delete(character);
    }

    private void apply(GameCharacter character, CharacterRequest request) {
        // 职业名归一化：Kanna / 阴阳师 / 칸나 是同一个职业，统一存标准名
        character.setJob(JobCatalog.canonical(request.job()));
        character.setPower(request.power() == null ? 0 : request.power());
        character.setMainChar(Boolean.TRUE.equals(request.mainChar()));
        character.setNote(request.note());
    }

    private static String normalizeQq(String qq) {
        if (qq == null || qq.isBlank()) {
            return null;
        }
        return qq.trim();
    }

    private void validateQq(String qq, Long selfId) {
        String normalized = normalizeQq(qq);
        if (normalized == null) {
            return;
        }
        if (!normalized.matches("\\d{5,12}")) {
            throw new BusinessException("QQ 号格式不正确：" + normalized);
        }
        playerRepository.findByQq(normalized).ifPresent(other -> {
            if (selfId == null || !other.getId().equals(selfId)) {
                throw new BusinessException("这个 QQ 号已经绑定给「" + other.getNickname() + "」了");
            }
        });
    }

    private int nextPlayerSort() {
        return (int) playerRepository.count() + 1;
    }

    static CharacterView toView(GameCharacter character) {
        return new CharacterView(
                character.getId(),
                character.getPlayer().getId(),
                character.getPlayer().getNickname(),
                character.getJob(),
                character.getPower(),
                character.isMainChar(),
                character.getNote(),
                character.getSortOrder());
    }
}
