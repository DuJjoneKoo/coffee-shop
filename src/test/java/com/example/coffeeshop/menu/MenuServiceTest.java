package com.example.coffeeshop.menu;

import com.example.coffeeshop.menu.domain.Menu;
import com.example.coffeeshop.menu.dto.MenuResponse;
import com.example.coffeeshop.menu.repository.MenuRepository;
import com.example.coffeeshop.menu.service.MenuService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * MenuService 단위 테스트.
 *
 * <p>Mockito 로 repository 만 mocking — Spring context 부팅 비용 없이 service 로직만 검증.
 */
@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock
    private MenuRepository menuRepository;

    @InjectMocks
    private MenuService menuService;

    @Test
    @DisplayName("메뉴가 여러 개 등록되어 있으면 모두 DTO 로 변환하여 반환한다")
    void returnsAllMenus() {
        // given
        Menu americano = Menu.builder().name("아메리카노").price(4500).build();
        Menu latte = Menu.builder().name("카페라떼").price(5000).build();
        given(menuRepository.findAll()).willReturn(List.of(americano, latte));

        // when
        List<MenuResponse> result = menuService.getAllMenus();

        // then
        assertThat(result).hasSize(2);
        assertThat(result).extracting(MenuResponse::name)
                .containsExactly("아메리카노", "카페라떼");
        assertThat(result).extracting(MenuResponse::price)
                .containsExactly(4500L, 5000L);
    }

    @Test
    @DisplayName("등록된 메뉴가 없으면 빈 리스트를 반환한다")
    void returnsEmptyWhenNoMenu() {
        // given
        given(menuRepository.findAll()).willReturn(List.of());

        // when
        List<MenuResponse> result = menuService.getAllMenus();

        // then
        assertThat(result).isEmpty();
    }
}
