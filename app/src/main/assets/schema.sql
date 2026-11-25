-- =====================================================================
-- MeReceipts: схема SQLite (жильцы, менеджеры УК, администратор)
-- Деньги хранятся в копейках (INTEGER). Даты: ISO-8601 текстом (UTC).
-- Включайте внешние ключи на КАЖДОМ соединении: PRAGMA foreign_keys = ON;
-- =====================================================================
PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------------
-- ТАБЛИЦЫ
-- ---------------------------------------------------------------------

CREATE TABLE management_companies (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE users (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    role                 TEXT    NOT NULL CHECK (role IN ('admin','manager','resident')),
    full_name            TEXT    NOT NULL CHECK (length(trim(full_name)) > 0),
    email                TEXT    NOT NULL UNIQUE COLLATE NOCASE CHECK (email LIKE '%_@_%._%'),
    phone                TEXT,
    password_hash        TEXT    NOT NULL,           -- хеш считает приложение (bcrypt/argon2), не SQL
    must_change_password INTEGER NOT NULL DEFAULT 0 CHECK (must_change_password IN (0,1)),
    is_active            INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1)),
    company_id           INTEGER REFERENCES management_companies(id) ON DELETE RESTRICT,
    created_at           TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at           TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    CHECK (role <> 'manager' OR company_id IS NOT NULL)   -- у менеджера обязательно есть УК
);
CREATE INDEX idx_users_role ON users(role);

CREATE TABLE addresses (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id        INTEGER REFERENCES users(id) ON DELETE CASCADE,   -- жилец-владелец
    company_id     INTEGER NOT NULL REFERENCES management_companies(id) ON DELETE RESTRICT,
    street_address TEXT    NOT NULL,
    account_number TEXT    NOT NULL UNIQUE,                          -- лицевой счёт
    is_primary     INTEGER NOT NULL DEFAULT 0 CHECK (is_primary IN (0,1))
);
CREATE INDEX idx_addresses_company ON addresses(company_id);
CREATE INDEX idx_addresses_user    ON addresses(user_id);

CREATE TABLE receipts (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    address_id     INTEGER NOT NULL REFERENCES addresses(id) ON DELETE CASCADE,
    period         TEXT    NOT NULL CHECK (period GLOB '[0-9][0-9][0-9][0-9]-[01][0-9]'),  -- '2026-11'
    due_date       TEXT    NOT NULL,                                                      -- '2026-11-25'
    payer_name     TEXT    NOT NULL,
    payment_status TEXT    NOT NULL DEFAULT 'unpaid' CHECK (payment_status IN ('unpaid','paid')),
    status_source  TEXT    NOT NULL DEFAULT 'auto'   CHECK (status_source  IN ('auto','manual')),
    status_comment TEXT,
    paid_at        TEXT,
    created_at     TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at     TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    UNIQUE (address_id, period)
);
CREATE INDEX idx_receipts_status ON receipts(payment_status);
CREATE INDEX idx_receipts_period ON receipts(period);

CREATE TABLE receipt_items (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    receipt_id   INTEGER NOT NULL REFERENCES receipts(id) ON DELETE CASCADE,
    service_code TEXT    NOT NULL CHECK (service_code IN ('heating','water','electricity','gas')),
    amount_kop   INTEGER NOT NULL CHECK (amount_kop >= 0),
    UNIQUE (receipt_id, service_code)
);

CREATE TABLE receipt_status_history (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    receipt_id  INTEGER NOT NULL REFERENCES receipts(id) ON DELETE CASCADE,
    old_status  TEXT    NOT NULL,
    new_status  TEXT    NOT NULL,
    source      TEXT    NOT NULL,                       -- 'auto' | 'manual'
    comment     TEXT,
    changed_by  INTEGER REFERENCES users(id) ON DELETE SET NULL,   -- NULL = система
    changed_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX idx_history_receipt ON receipt_status_history(receipt_id);

-- «Контекст сессии»: кто сейчас выполняет операцию (его выставляют процедуры ниже).
CREATE TABLE app_context (
    key   TEXT PRIMARY KEY,
    value TEXT
);

-- ---------------------------------------------------------------------
-- ПРЕДСТАВЛЕНИЯ (3)
-- ---------------------------------------------------------------------

-- 1) Экран админа «Аккаунты УК»
CREATE VIEW v_managers AS
SELECT u.id,
       u.full_name,
       u.email,
       u.phone,
       u.company_id,
       c.name AS company_name,
       u.is_active,
       CASE u.is_active WHEN 1 THEN 'Активен' ELSE 'Заблокирован' END AS status_label,
       substr(u.full_name, 1, 1) AS initial,
       u.created_at
FROM users u
JOIN management_companies c ON c.id = u.company_id
WHERE u.role = 'manager';

-- 2) Списки квитанций (менеджер фильтрует по company_id, админ видит все)
CREATE VIEW v_receipts_list AS
SELECT r.id,
       a.company_id,
       c.name AS company_name,
       a.street_address,
       a.account_number,
       r.payer_name,
       r.period,
       r.due_date,
       COALESCE((SELECT SUM(i.amount_kop) FROM receipt_items i WHERE i.receipt_id = r.id), 0) AS total_kop,
       r.payment_status,
       CASE r.payment_status WHEN 'paid' THEN 'Оплачено' ELSE 'Не оплачено' END AS status_label,
       (r.status_source = 'manual') AS is_manual,
       r.paid_at,
       r.updated_at
FROM receipts r
JOIN addresses a ON a.id = r.address_id
JOIN management_companies c ON c.id = a.company_id;

-- 3) Экран админа «Редактирование квитанции»: услуги в одну строку
CREATE VIEW v_receipt_edit AS
SELECT r.id AS receipt_id,
       a.id AS address_id,
       a.company_id,
       c.name AS company_name,
       a.street_address,
       a.account_number,
       r.payer_name,
       r.period,
       r.due_date,
       COALESCE(SUM(CASE i.service_code WHEN 'heating'     THEN i.amount_kop END), 0) AS heating_kop,
       COALESCE(SUM(CASE i.service_code WHEN 'water'       THEN i.amount_kop END), 0) AS water_kop,
       COALESCE(SUM(CASE i.service_code WHEN 'electricity' THEN i.amount_kop END), 0) AS electricity_kop,
       COALESCE(SUM(CASE i.service_code WHEN 'gas'         THEN i.amount_kop END), 0) AS gas_kop,
       COALESCE(SUM(i.amount_kop), 0) AS total_kop,
       r.payment_status,
       r.status_source,
       r.status_comment
FROM receipts r
JOIN addresses a ON a.id = r.address_id
JOIN management_companies c ON c.id = a.company_id
LEFT JOIN receipt_items i ON i.receipt_id = r.id
GROUP BY r.id;

-- ---------------------------------------------------------------------
-- ТРИГГЕРЫ (3)
-- ---------------------------------------------------------------------

-- 1) Права на ручную смену статуса: админ — любые, менеджер — только квитанции своей УК
CREATE TRIGGER trg_receipts_status_scope
BEFORE UPDATE OF payment_status ON receipts
WHEN NEW.status_source = 'manual' AND OLD.payment_status <> NEW.payment_status
BEGIN
    SELECT RAISE(ABORT, 'Нет прав на изменение статуса этой квитанции')
    WHERE NOT EXISTS (
        SELECT 1
        FROM users u
        WHERE u.id = (SELECT CAST(value AS INTEGER) FROM app_context WHERE key = 'current_user_id')
          AND u.is_active = 1
          AND ( u.role = 'admin'
                OR (u.role = 'manager'
                    AND u.company_id = (SELECT company_id FROM addresses WHERE id = NEW.address_id)) )
    );
END;

-- 2) История статусов + paid_at + updated_at (и для автоматических, и для ручных изменений)
CREATE TRIGGER trg_receipts_status_history
AFTER UPDATE OF payment_status ON receipts
WHEN OLD.payment_status <> NEW.payment_status
BEGIN
    INSERT INTO receipt_status_history (receipt_id, old_status, new_status, source, comment, changed_by)
    VALUES (NEW.id, OLD.payment_status, NEW.payment_status, NEW.status_source, NEW.status_comment,
            (SELECT CAST(value AS INTEGER) FROM app_context WHERE key = 'current_user_id'));

    UPDATE receipts
    SET paid_at    = CASE NEW.payment_status WHEN 'paid' THEN strftime('%Y-%m-%dT%H:%M:%fZ','now') ELSE NULL END,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
    WHERE id = NEW.id;
END;

-- 3) Нельзя оставить систему без активного администратора
CREATE TRIGGER trg_users_keep_one_admin
BEFORE UPDATE OF role, is_active ON users
WHEN OLD.role = 'admin' AND (NEW.role <> 'admin' OR NEW.is_active = 0)
BEGIN
    SELECT RAISE(ABORT, 'Нельзя убрать или заблокировать последнего администратора')
    WHERE NOT EXISTS (SELECT 1 FROM users WHERE role = 'admin' AND is_active = 1 AND id <> OLD.id);
END;

-- ---------------------------------------------------------------------
-- «ХРАНИМЫЕ ПРОЦЕДУРЫ» (3) — эмуляция через представление + INSTEAD OF
-- Вызов:  INSERT INTO sp_xxx (параметры) VALUES (...);
-- Любая ошибка откатывает весь вызов целиком (RAISE(ABORT, ...)).
-- ---------------------------------------------------------------------

-- 1) Админ создаёт аккаунт менеджера УК (УК создаётся автоматически, если её ещё нет).
--    Узнать id нового менеджера: SELECT id FROM users WHERE email = ?;
CREATE VIEW sp_create_manager AS
SELECT NULL AS actor_id, NULL AS full_name, NULL AS email, NULL AS phone,
       NULL AS company_name, NULL AS password_hash;

CREATE TRIGGER sp_create_manager_call
INSTEAD OF INSERT ON sp_create_manager
BEGIN
    SELECT RAISE(ABORT, 'Только администратор может создавать аккаунты')
    WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = NEW.actor_id AND role = 'admin' AND is_active = 1);

    SELECT RAISE(ABORT, 'Укажите управляющую компанию')
    WHERE NEW.company_name IS NULL OR length(trim(NEW.company_name)) = 0;

    INSERT OR IGNORE INTO management_companies (name) VALUES (trim(NEW.company_name));

    INSERT INTO users (role, full_name, email, phone, password_hash, must_change_password, company_id)
    VALUES ('manager', trim(NEW.full_name), trim(NEW.email), NEW.phone, NEW.password_hash, 1,
            (SELECT id FROM management_companies WHERE name = trim(NEW.company_name)));
END;

-- 2) Ручная смена статуса оплаты (менеджер своей УК или админ)
CREATE VIEW sp_set_receipt_status AS
SELECT NULL AS actor_id, NULL AS receipt_id, NULL AS new_status, NULL AS comment;

CREATE TRIGGER sp_set_receipt_status_call
INSTEAD OF INSERT ON sp_set_receipt_status
BEGIN
    SELECT RAISE(ABORT, 'Квитанция не найдена')
    WHERE NOT EXISTS (SELECT 1 FROM receipts WHERE id = NEW.receipt_id);

    SELECT RAISE(ABORT, 'Недопустимый статус')
    WHERE NEW.new_status IS NULL OR NEW.new_status NOT IN ('unpaid','paid');

    INSERT OR REPLACE INTO app_context (key, value) VALUES ('current_user_id', NEW.actor_id);

    UPDATE receipts
    SET payment_status = NEW.new_status,
        status_source  = 'manual',
        status_comment = NEW.comment
    WHERE id = NEW.receipt_id AND payment_status <> NEW.new_status;

    DELETE FROM app_context WHERE key = 'current_user_id';
END;

-- 3) Админ редактирует все данные квитанции (адрес, счёт, плательщик, УК, период, срок, суммы, статус)
CREATE VIEW sp_update_receipt AS
SELECT NULL AS actor_id, NULL AS receipt_id,
       NULL AS street_address, NULL AS account_number, NULL AS payer_name, NULL AS company_id,
       NULL AS period, NULL AS due_date,
       NULL AS heating_kop, NULL AS water_kop, NULL AS electricity_kop, NULL AS gas_kop,
       NULL AS payment_status, NULL AS comment;

CREATE TRIGGER sp_update_receipt_call
INSTEAD OF INSERT ON sp_update_receipt
BEGIN
    SELECT RAISE(ABORT, 'Только администратор может менять все данные квитанции')
    WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = NEW.actor_id AND role = 'admin' AND is_active = 1);

    SELECT RAISE(ABORT, 'Квитанция не найдена')
    WHERE NOT EXISTS (SELECT 1 FROM receipts WHERE id = NEW.receipt_id);

    SELECT RAISE(ABORT, 'Недопустимый статус')
    WHERE NEW.payment_status IS NULL OR NEW.payment_status NOT IN ('unpaid','paid');

    UPDATE addresses
    SET street_address = NEW.street_address,
        account_number = NEW.account_number,
        company_id     = NEW.company_id
    WHERE id = (SELECT address_id FROM receipts WHERE id = NEW.receipt_id);

    UPDATE receipts
    SET payer_name = NEW.payer_name,
        period     = NEW.period,
        due_date   = NEW.due_date,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
    WHERE id = NEW.receipt_id;

    INSERT OR REPLACE INTO receipt_items (receipt_id, service_code, amount_kop) VALUES (NEW.receipt_id, 'heating',     NEW.heating_kop);
    INSERT OR REPLACE INTO receipt_items (receipt_id, service_code, amount_kop) VALUES (NEW.receipt_id, 'water',       NEW.water_kop);
    INSERT OR REPLACE INTO receipt_items (receipt_id, service_code, amount_kop) VALUES (NEW.receipt_id, 'electricity', NEW.electricity_kop);
    INSERT OR REPLACE INTO receipt_items (receipt_id, service_code, amount_kop) VALUES (NEW.receipt_id, 'gas',         NEW.gas_kop);

    INSERT OR REPLACE INTO app_context (key, value) VALUES ('current_user_id', NEW.actor_id);

    UPDATE receipts
    SET payment_status = NEW.payment_status,
        status_source  = 'manual',
        status_comment = NEW.comment
    WHERE id = NEW.receipt_id AND payment_status <> NEW.payment_status;

    DELETE FROM app_context WHERE key = 'current_user_id';
END;
