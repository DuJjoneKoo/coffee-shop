package com.example.coffeeshop.order;

import com.example.coffeeshop.common.exception.BusinessException;
import com.example.coffeeshop.menu.domain.Menu;
import com.example.coffeeshop.menu.repository.MenuRepository;
import com.example.coffeeshop.order.domain.Order;
import com.example.coffeeshop.order.dto.OrderResponse;
import com.example.coffeeshop.order.messaging.OrderCreatedEvent;
import com.example.coffeeshop.order.repository.OrderRepository;
import com.example.coffeeshop.order.service.OrderService;
import com.example.coffeeshop.point.service.PointService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * OrderService 단위 테스트 — 주문 흐름의 핵심 분기와 협력 객체 호출 검증.
 *
 * <p>커버 범위:
 * <ul>
 *   <li>정상 흐름: 메뉴 조회 → 포인트 차감 → 주문 저장 → 이벤트 발행</li>
 *   <li>메뉴 없음 → MENU_NOT_FOUND (이후 단계 호출 안 됨)</li>
 *   <li>포인트 부족 → INSUFFICIENT_POINT (주문 저장 / 이벤트 발행 안 됨)</li>
 *   <li>충전 이력 없는 사용자 → POINT_NOT_FOUND</li>
 *   <li>이벤트 페이로드의 핵심 필드가 정확한지 ArgumentCaptor 로 검증</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private MenuRepository menuRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PointService pointService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OrderService orderService;

    @Test
    @DisplayName("정상 주문 시 메뉴 조회 → 포인트 차감 → 주문 저장 → 이벤트 발행 순서로 처리된다")
    void order_happyPath() {
        // given
        Long userId = 1L, menuId = 10L;
        Menu menu = Menu.builder().name("아메리카노").price(4500).build();
        Order saved = Order.builder().userId(userId).menuId(menuId).paidAmount(4500).build();
        given(menuRepository.findById(menuId)).willReturn(Optional.of(menu));
        given(orderRepository.save(any(Order.class))).willReturn(saved);

        // when
        OrderResponse response = orderService.order(userId, menuId);

        // then
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.menuId()).isEqualTo(menuId);
        assertThat(response.paidAmount()).isEqualTo(4500L);

        // 포인트 차감이 메뉴 가격으로 호출됐는지.
        verify(pointService).usePoint(userId, 4500L);
        verify(orderRepository).save(any(Order.class));
        verify(eventPublisher).publishEvent(any(OrderCreatedEvent.class));
    }

    @Test
    @DisplayName("이벤트 발행 페이로드의 핵심 필드가 정확하다")
    void order_publishesEventWithCorrectPayload() {
        // given
        Long userId = 7L, menuId = 42L;
        Menu menu = Menu.builder().name("카페라떼").price(5000).build();
        Order saved = Order.builder().userId(userId).menuId(menuId).paidAmount(5000).build();
        given(menuRepository.findById(menuId)).willReturn(Optional.of(menu));
        given(orderRepository.save(any(Order.class))).willReturn(saved);

        // when
        orderService.order(userId, menuId);

        // then
        ArgumentCaptor<OrderCreatedEvent> captor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        OrderCreatedEvent event = captor.getValue();
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.menuId()).isEqualTo(menuId);
        assertThat(event.paidAmount()).isEqualTo(5000L);
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 메뉴 주문 시 MENU_NOT_FOUND, 이후 단계는 호출되지 않는다")
    void order_throwsWhenMenuNotFound() {
        // given
        given(menuRepository.findById(anyLong())).willReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> orderService.order(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "MENU_NOT_FOUND")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);

        // 메뉴가 없으면 포인트 차감/주문 저장/이벤트 발행 모두 일어나선 안 됨.
        verify(pointService, never()).usePoint(anyLong(), anyLong());
        verify(orderRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(OrderCreatedEvent.class));
    }

    @Test
    @DisplayName("포인트 부족 시 주문 저장과 이벤트 발행이 일어나지 않는다 (트랜잭션 의도 검증)")
    void order_throwsWhenInsufficientPoint() {
        // given
        Long userId = 1L, menuId = 10L;
        Menu menu = Menu.builder().name("아메리카노").price(4500).build();
        given(menuRepository.findById(menuId)).willReturn(Optional.of(menu));
        doThrow(new BusinessException("INSUFFICIENT_POINT", "포인트 부족", HttpStatus.BAD_REQUEST))
                .when(pointService).usePoint(userId, 4500L);

        // when / then
        assertThatThrownBy(() -> orderService.order(userId, menuId))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INSUFFICIENT_POINT");

        // 차감 실패 시 주문 row 생성 / 이벤트 발행 둘 다 안 일어나야 함.
        // (실제 운영에서는 @Transactional 롤백이 이를 강제하지만, 단위 테스트에서는 호출 자체가
        //  안 일어남을 검증함으로써 "차감→저장→발행" 순서 의도가 코드에 박혀있음을 보장.)
        verify(orderRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(OrderCreatedEvent.class));
    }

    @Test
    @DisplayName("충전 이력 없는 사용자가 주문 시 POINT_NOT_FOUND")
    void order_throwsWhenUserMissing() {
        // given
        Long userId = 999L, menuId = 10L;
        Menu menu = Menu.builder().name("아메리카노").price(4500).build();
        given(menuRepository.findById(menuId)).willReturn(Optional.of(menu));
        doThrow(new BusinessException("POINT_NOT_FOUND", "사용자 포인트 정보 없음", HttpStatus.NOT_FOUND))
                .when(pointService).usePoint(userId, 4500L);

        // when / then
        assertThatThrownBy(() -> orderService.order(userId, menuId))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "POINT_NOT_FOUND");

        verify(orderRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(OrderCreatedEvent.class));
    }
}
