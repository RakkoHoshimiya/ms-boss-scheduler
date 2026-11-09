package com.gmsr.scheduler.voice;

import java.util.List;

/** 语音频道定义。房间是固定的，前端按 id 进房。 */
public final class VoiceRoom {

    /** 一个语音频道。 */
    public record Room(String id, String name, String description, String icon) {
    }

    /** 当前开放的频道列表（最多 3 个，随时可改）。 */
    public static final List<Room> ROOMS = List.of(
            new Room("boss-1", "语音频道 1", "1 号 BOSS 攻坚频道", "🎙"),
            new Room("boss-2", "语音频道 2", "2 号 BOSS 攻坚频道", "🎙"),
            new Room("boss-3", "语音频道 3", "3 号 BOSS 攻坚频道", "🎙"));

    public static boolean exists(String roomId) {
        return ROOMS.stream().anyMatch(room -> room.id().equals(roomId));
    }

    private VoiceRoom() {
    }
}
