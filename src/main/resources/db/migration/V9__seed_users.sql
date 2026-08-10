-- Demo accounts for local dev / M2 auth testing.
-- Both use bcrypt hash of password "Password123!".
INSERT INTO users (email, password_hash) VALUES
    ('alice@example.com', '$2b$10$Y7nD6YrhkCSgFhfU2Fb8vOWPeMPtjoqCX2WZSxJUKG5h0p9mmsTda'),
    ('bob@example.com', '$2b$10$Y7nD6YrhkCSgFhfU2Fb8vOWPeMPtjoqCX2WZSxJUKG5h0p9mmsTda');
