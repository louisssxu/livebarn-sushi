DROP TABLE IF EXISTS sushi;
CREATE TABLE sushi (
                       id INT AUTO_INCREMENT PRIMARY KEY,
                       name VARCHAR(30),
                       time_to_make INT DEFAULT NULL
);

DROP TABLE IF EXISTS sushi_order;
CREATE TABLE sushi_order (
                             id INT AUTO_INCREMENT PRIMARY KEY,
                             status_id INT NOT NULL,
                             sushi_id INT NOT NULL,
                             created_at TIMESTAMP NOT NULL default CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS status;
CREATE TABLE status (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        name VARCHAR(30) NOT NULL
);

INSERT INTO sushi (name, time_to_make) VALUES
                                           ('California Roll', 1),
                                           ('Kamikaze Roll', 5),
                                           ('Dragon Eye', 5);

INSERT INTO status (name) VALUES
                              ('created'),
                              ('in-progress'),
                              ('paused'),
                              ('resumed'),
                              ('finished'),
                              ('cancelled');

INSERT INTO sushi_order (status_id, sushi_id) VALUES (1, 1);
