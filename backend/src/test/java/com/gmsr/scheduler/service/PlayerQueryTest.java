package com.gmsr.scheduler.service;
import com.gmsr.scheduler.domain.*;
import com.gmsr.scheduler.repository.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PlayerQueryTest {
 @Test void singlePlayerDoesNotLoadAllPlayersOrCharacters(){
  var players=mock(PlayerRepository.class);var chars=mock(GameCharacterRepository.class);
  var player=new Player();player.setId(1L);player.setNickname("fixture");player.setQq("123456789");
  when(players.findById(1L)).thenReturn(Optional.of(player));when(chars.findByPlayerIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
  var service=new PlayerService(players,mock(AppUserRepository.class),chars,mock(RunMemberRepository.class));
  var view=service.get(1L);assertEquals(1L,view.id());assertNull(view.qq());assertTrue(view.hasQq());
  verify(players).findById(1L);verify(chars).findByPlayerIdOrderBySortOrderAscIdAsc(1L);verifyNoMoreInteractions(players,chars);
 }
 @Test void groupedListKeepsPowerOrderingAndQqRedaction(){
  var players=mock(PlayerRepository.class);var chars=mock(GameCharacterRepository.class);
  Player a=new Player();a.setId(1L);a.setNickname("a");a.setQq("123456789");Player b=new Player();b.setId(2L);b.setNickname("b");
  GameCharacter low=new GameCharacter();low.setId(10L);low.setPlayer(a);low.setPower(1);GameCharacter high=new GameCharacter();high.setId(11L);high.setPlayer(b);high.setPower(9);
  when(players.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(a,b));when(chars.findAllByOrderByPlayerSortOrderAscSortOrderAscIdAsc()).thenReturn(List.of(low,high));
  var service=new PlayerService(players,mock(AppUserRepository.class),chars,mock(RunMemberRepository.class));
  var views=service.list();assertEquals(List.of(2L,1L),views.stream().map(v->v.id()).toList());assertEquals(9,views.getFirst().characters().getFirst().power());assertNull(views.get(1).qq());
 }
}
