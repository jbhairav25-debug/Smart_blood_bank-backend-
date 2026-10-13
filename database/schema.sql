-- ============================================================
--  Smart Blood Bank & Donor Management System
--  database/schema.sql  —  Run this ONCE to set up the DB
-- ============================================================

-- 1. Create (or reset) the database
DROP DATABASE IF EXISTS blood_bank_db;
CREATE DATABASE blood_bank_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE blood_bank_db;

-- ============================================================
--  TABLE: users
--  Stores all accounts: admin, donor, hospital
-- ============================================================
CREATE TABLE users (
  id         INT          AUTO_INCREMENT PRIMARY KEY,
  name       VARCHAR(100) NOT NULL,
  email      VARCHAR(150) NOT NULL UNIQUE,
  password   VARCHAR(255) NOT NULL,          -- bcrypt hashed
  role       ENUM('admin','donor','hospital') NOT NULL DEFAULT 'donor',
  created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
--  TABLE: donors
--  Extended profile for users with role = 'donor'
-- ============================================================
CREATE TABLE donors (
  id            INT         AUTO_INCREMENT PRIMARY KEY,
  user_id       INT         NOT NULL UNIQUE,
  blood_group   ENUM('A+','A-','B+','B-','AB+','AB-','O+','O-') NOT NULL,
  location      VARCHAR(150) NOT NULL,
  last_donation DATE         DEFAULT NULL,
  created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ============================================================
--  TABLE: blood_stock
--  One row per blood group, tracks available units
-- ============================================================
CREATE TABLE blood_stock (
  blood_group      ENUM('A+','A-','B+','B-','AB+','AB-','O+','O-') PRIMARY KEY,
  units_available  INT         NOT NULL DEFAULT 0,
  updated_at       TIMESTAMP   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- ============================================================
--  TABLE: requests
--  Blood requests raised by hospitals or donors
-- ============================================================
CREATE TABLE requests (
  id           INT          AUTO_INCREMENT PRIMARY KEY,
  requester_id INT          NOT NULL,
  blood_group  ENUM('A+','A-','B+','B-','AB+','AB-','O+','O-') NOT NULL,
  units        INT          NOT NULL,
  urgency      ENUM('normal','urgent','emergency') NOT NULL DEFAULT 'normal',
  notes        TEXT         DEFAULT NULL,
  status       ENUM('pending','approved','rejected') NOT NULL DEFAULT 'pending',
  created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (requester_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ============================================================
--  TABLE: donations
--  Records each blood donation event
-- ============================================================
CREATE TABLE donations (
  id        INT       AUTO_INCREMENT PRIMARY KEY,
  donor_id  INT       NOT NULL,
  units     INT       NOT NULL DEFAULT 1,
  notes     TEXT      DEFAULT NULL,
  date      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (donor_id) REFERENCES donors(id) ON DELETE CASCADE
);

-- ============================================================
--  SEED DATA
-- ============================================================

-- ── Blood stock (start with some units for demo) ──────────
INSERT INTO blood_stock (blood_group, units_available) VALUES
  ('A+',  45),
  ('A-',  12),
  ('B+',  38),
  ('B-',   8),
  ('AB+', 15),
  ('AB-',  5),
  ('O+',  52),
  ('O-',  18);

-- ── Admin user  (password: Admin@123) ─────────────────────
-- bcrypt hash of "Admin@123" with salt rounds = 12
INSERT INTO users (name, email, password, role) VALUES
  ('Admin User', 'admin@bloodbank.com',
   '$2a$12$0z.pHIL1aTCyFRpbZ3L3X.2x0oJo0H7W1AoWQFBdYigtN6R4OfGky',
   'admin');

-- ── Hospital account  (password: Hospital@123) ────────────
INSERT INTO users (name, email, password, role) VALUES
  ('City General Hospital', 'hospital@citygeneral.com',
   '$2a$12$tHmvCvVt5h1R4IqPe5xr6u.c/K9Bqv0TI3bOjrHi0Pm0EqFW1mnuS',
   'hospital');

-- ── Donor accounts  (password: Donor@123) ─────────────────
INSERT INTO users (name, email, password, role) VALUES
  ('Rahul Sharma',  'rahul@example.com',
   '$2a$12$KeRcqmXuSFKIqUFQhBZ2fOH7NJJKVvJiXPw5ow/9MHIRkOuCqIYZW', 'donor'),
  ('Priya Patel',   'priya@example.com',
   '$2a$12$KeRcqmXuSFKIqUFQhBZ2fOH7NJJKVvJiXPw5ow/9MHIRkOuCqIYZW', 'donor'),
  ('Arjun Singh',   'arjun@example.com',
   '$2a$12$KeRcqmXuSFKIqUFQhBZ2fOH7NJJKVvJiXPw5ow/9MHIRkOuCqIYZW', 'donor'),
  ('Sneha Reddy',   'sneha@example.com',
   '$2a$12$KeRcqmXuSFKIqUFQhBZ2fOH7NJJKVvJiXPw5ow/9MHIRkOuCqIYZW', 'donor'),
  ('Kiran Kumar',   'kiran@example.com',
   '$2a$12$KeRcqmXuSFKIqUFQhBZ2fOH7NJJKVvJiXPw5ow/9MHIRkOuCqIYZW', 'donor');

-- ── Donor profiles ─────────────────────────────────────────
-- user_id 3 = Rahul, 4 = Priya, 5 = Arjun, 6 = Sneha, 7 = Kiran
INSERT INTO donors (user_id, blood_group, location, last_donation) VALUES
  (3, 'O+',  'Hyderabad',  '2024-11-10'),
  (4, 'B+',  'Mumbai',     '2024-10-05'),
  (5, 'A+',  'Delhi',      '2025-01-15'),
  (6, 'AB-', 'Bangalore',  '2024-09-20'),
  (7, 'O-',  'Chennai',    '2025-02-28');

-- ── Sample donations ───────────────────────────────────────
INSERT INTO donations (donor_id, units, notes, date) VALUES
  (1, 1, 'Voluntary camp donation', '2024-11-10'),
  (2, 1, 'Regular donation',        '2024-10-05'),
  (3, 2, 'Emergency drive',         '2025-01-15'),
  (4, 1, 'First donation',          '2024-09-20'),
  (5, 1, 'Blood camp donation',     '2025-02-28');

-- ── Sample requests ────────────────────────────────────────
INSERT INTO requests (requester_id, blood_group, units, urgency, notes, status) VALUES
  (2, 'O+',  3, 'emergency', 'Accident victim needs blood urgently', 'pending'),
  (2, 'B+',  2, 'urgent',    'Surgery scheduled tomorrow morning',   'approved'),
  (2, 'A-',  1, 'normal',    'Routine transfusion for patient',      'rejected'),
  (2, 'AB+', 2, 'normal',    'Planned surgery next week',            'pending');

-- ============================================================
--  Verify setup
-- ============================================================
SELECT 'Setup complete!' AS status;
SELECT blood_group, units_available FROM blood_stock;
SELECT id, name, role FROM users;
