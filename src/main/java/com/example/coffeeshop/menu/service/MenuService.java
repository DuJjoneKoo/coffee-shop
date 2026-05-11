package com.example.coffeeshop.menu.service;

import com.example.coffeeshop.menu.dto.MenuResponse;
import com.example.coffeeshop.menu.repository.MenuRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 메뉴 조회 서비스.
 *
 * <p>설계 메모:
 * <ul>
 *   <li>메뉴는 변경 빈도가 매우 낮고 조회는 매우 잦음 -> Redis 캐싱 후보 1순위.</li>
 *   <li>다중 인스턴스에서도 캐시 무효화는 단일 Redis 를 공유하므로 자동 일관성 유지.</li>
 *   <li>TODO: @Cacheable("menus") 추가하고 admin 변경 API 에 @CacheEvict 적용.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuService {

    private final MenuRepository menuRepository;

    public List<MenuResponse> getAllMenus() {
        return menuRepository.findAll().stream()
                .map(MenuResponse::from)
                .toList();
    }
}
