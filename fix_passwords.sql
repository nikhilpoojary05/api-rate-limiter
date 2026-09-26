-- Fix passwords with valid BCrypt hashes
-- Admin@123!
UPDATE app_users SET password = '$2b$10$oeXSldFWfPZ1Oxn0Cm3xHOSSrSzIDIMELgbfQ00sRW/N/Uf68d78e' WHERE username = 'admin';
-- Test@123!
UPDATE app_users SET password = '$2b$10$fxJcgJVXHYvzslTzBA3DTusw26aIV6fbe4W0wYGcUVzyo8zgCu.Ce' WHERE username = 'john.doe';
UPDATE app_users SET password = '$2b$10$fxJcgJVXHYvzslTzBA3DTusw26aIV6fbe4W0wYGcUVzyo8zgCu.Ce' WHERE username = 'jane.smith';

SELECT username, SUBSTRING(password, 1, 7) AS prefix FROM app_users;
