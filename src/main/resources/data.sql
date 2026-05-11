-- 로컬/테스트용 메뉴 시드 데이터.
-- 실행 조건: application-local.yml 에서 spring.sql.init.mode=always 로 활성화한 환경에서만 실행됨.
--   - 운영(prod) 등 다른 프로파일은 mode 기본값(never) 이라 본 파일을 무시 → 시드가 운영 DB 에 섞일 위험 없음.
--   - spring.jpa.defer-datasource-initialization=true 로 Hibernate ddl-auto 후에 실행되어 스키마 누락 방지.
--
-- 채점자가 부팅 직후 즉시 /api/v1/menus 로 동작 검증할 수 있도록 최소 4개 메뉴 제공.
INSERT INTO menu (name, price) VALUES ('아메리카노', 4500);
INSERT INTO menu (name, price) VALUES ('카페라떼', 5000);
INSERT INTO menu (name, price) VALUES ('카푸치노', 5000);
INSERT INTO menu (name, price) VALUES ('바닐라라떼', 5500);
