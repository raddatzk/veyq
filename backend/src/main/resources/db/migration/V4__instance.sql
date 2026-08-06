-- Instance-wide constant: the public auth salt, generated once at first run and stable for the life of
-- the instance. Feeds authSalt = HKDF(instanceSalt, normalizedEmail) on the CLIENT at both registration
-- and login (ADR 0003). Public, not a secret; the server only stores and serves it.

CREATE TABLE veyq_instance (
    id            INT NOT NULL PRIMARY KEY,
    instance_salt TEXT NOT NULL
);
