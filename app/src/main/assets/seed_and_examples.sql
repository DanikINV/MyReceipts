-- =====================================================================
-- Тестовые данные и примеры вызовов (выполнять после schema.sql)
-- password_hash здесь — заглушки, в приложении кладите настоящий хеш.
-- =====================================================================

INSERT INTO management_companies (name) VALUES ('УК «Студенческая»'), ('УК «Центр»');

INSERT INTO users (role, full_name, email, phone, password_hash, company_id) VALUES
 ('admin',    'Администратор Системы',   'admin@mereceipts.app',  NULL,          'hash_admin',   NULL),
 ('manager',  'Иванова Мария Петровна',  'm.ivanova@uk.ru',       '+79001112233','hash_manager', 1),
 ('manager',  'Сидоров Олег Игоревич',   'o.sidorov@centr.ru',    '+79004445566','hash_manager', 2),
 ('resident', 'Петров Пётр',             'yandex@yandex.ru',      NULL,          'hash_res',     NULL);

INSERT INTO addresses (user_id, company_id, street_address, account_number, is_primary) VALUES
 (4, 1, 'ул. Студенческая, 12, кв. 34', '4012345', 1),
 (4, 2, 'Общежитие №3, комн. 217',      '5098765', 0);

INSERT INTO receipts (address_id, period, due_date, payer_name, payment_status, status_source, paid_at) VALUES
 (1, '2026-10', '2026-10-25', 'Петров Пётр', 'paid',   'auto', '2026-10-12T09:00:00.000Z'),
 (1, '2026-11', '2026-11-25', 'Петров Пётр', 'unpaid', 'auto', NULL),
 (2, '2026-11', '2026-11-25', 'Петров Пётр', 'unpaid', 'auto', NULL);

INSERT INTO receipt_items (receipt_id, service_code, amount_kop) VALUES
 (1,'heating',152000),(1,'water',90000),(1,'electricity',75000),(1,'gas',155000),
 (2,'heating',158000),(2,'water',95000),(2,'electricity',78000),(2,'gas',134000),
 (3,'heating',90000), (3,'water',40000),(3,'electricity',30000),(3,'gas',20000);

-- ---------------------------------------------------------------------
-- ПРИМЕРЫ
-- ---------------------------------------------------------------------

-- Экран «Аккаунты УК» (поиск по ASCII-части; для кириллицы без учёта регистра — искать в Kotlin
-- или хранить нормализованную колонку: LIKE в SQLite регистронезависим только для ASCII)
-- SELECT * FROM v_managers ORDER BY full_name;

-- Экран менеджера: квитанции своей УК с фильтром «Не оплачено»
-- SELECT * FROM v_receipts_list WHERE company_id = :myCompanyId AND payment_status = 'unpaid' ORDER BY due_date;

-- Админ: создать менеджера
-- INSERT INTO sp_create_manager (actor_id, full_name, email, phone, company_name, password_hash)
-- VALUES (1, 'Новикова Анна', 'a.novikova@uk.ru', '+79007778899', 'УК «Север»', '<hash>');

-- Менеджер/админ: отметить квитанцию оплаченной вручную
-- INSERT INTO sp_set_receipt_status (actor_id, receipt_id, new_status, comment)
-- VALUES (2, 2, 'paid', 'Оплата по чеку из банка');

-- Админ: изменить все данные квитанции (суммы в копейках)
-- INSERT INTO sp_update_receipt (actor_id, receipt_id, street_address, account_number, payer_name, company_id,
--                                period, due_date, heating_kop, water_kop, electricity_kop, gas_kop,
--                                payment_status, comment)
-- VALUES (1, 2, 'ул. Студенческая, 12, кв. 34', '4012345', 'Петров Пётр', 1,
--         '2026-11', '2026-11-28', 160000, 95000, 78000, 134000, 'unpaid', 'Пересчёт отопления');

-- Автоматическая оплата (платёжный шлюз): статус source='auto', контекст пользователя пуст → changed_by = NULL
-- UPDATE receipts SET payment_status = 'paid', status_source = 'auto', status_comment = NULL WHERE id = 3;
