CREATE TABLE IF NOT EXISTS fiat_currency (
    fiat_id SERIAL PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL UNIQUE,
    name VARCHAR(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS "user" (
    user_id SERIAL PRIMARY KEY,
    chat_id VARCHAR(50) NOT NULL UNIQUE,
    fiat_id INTEGER REFERENCES fiat_currency(fiat_id)
);

CREATE TABLE IF NOT EXISTS crypto_currency (
    crypto_currency_id SERIAL PRIMARY KEY,
    symbol VARCHAR(10) NOT NULL UNIQUE,
    name VARCHAR(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS tracked_currency (
    tracked_currency_id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES "user"(user_id),
    crypto_currency_id INTEGER REFERENCES crypto_currency(crypto_currency_id),
    target_price DECIMAL(20, 8),
    UNIQUE(user_id, crypto_currency_id)
);

CREATE TABLE IF NOT EXISTS portfel (
    portfel_id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES "user"(user_id),
    crypto_currency_id INTEGER REFERENCES crypto_currency(crypto_currency_id),
    amount DECIMAL(20, 8) NOT NULL,
    UNIQUE(user_id, crypto_currency_id)
);

CREATE TABLE IF NOT EXISTS transaction (
    transaction_id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES "user"(user_id),
    crypto_currency_id INTEGER REFERENCES crypto_currency(crypto_currency_id),
    amount DECIMAL(20, 8) NOT NULL,
    price DECIMAL(20, 8) NOT NULL,
    date TIMESTAMP NOT NULL,
    type VARCHAR(10) NOT NULL -- 'BUY' or 'SELL'
);

CREATE TABLE IF NOT EXISTS alert_history (
    record_alert_id SERIAL PRIMARY KEY,
    tracked_currency_id INTEGER REFERENCES tracked_currency(tracked_currency_id),
    date_record TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS llm_history (
    request_id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES "user"(user_id),
    user_request TEXT NOT NULL,
    llm_response TEXT NOT NULL,
    request_type VARCHAR(50) NOT NULL
);

-- Insert default fiat currencies
INSERT INTO fiat_currency (symbol, name) VALUES ('USD', 'US Dollar') ON CONFLICT (symbol) DO NOTHING;
INSERT INTO fiat_currency (symbol, name) VALUES ('EUR', 'Euro') ON CONFLICT (symbol) DO NOTHING;
INSERT INTO fiat_currency (symbol, name) VALUES ('JPY', 'Japanese Yen') ON CONFLICT (symbol) DO NOTHING;
INSERT INTO fiat_currency (symbol, name) VALUES ('GBP', 'British Pound') ON CONFLICT (symbol) DO NOTHING;
INSERT INTO fiat_currency (symbol, name) VALUES ('RUB', 'Russian Ruble') ON CONFLICT (symbol) DO NOTHING;
INSERT INTO fiat_currency (symbol, name) VALUES ('CNY', 'Chinese Yuan') ON CONFLICT (symbol) DO NOTHING;

-- Insert default crypto currencies
INSERT INTO crypto_currency (symbol, name) VALUES ('BTC', 'Bitcoin') ON CONFLICT (symbol) DO NOTHING;
INSERT INTO crypto_currency (symbol, name) VALUES ('ETH', 'Ethereum') ON CONFLICT (symbol) DO NOTHING;
INSERT INTO crypto_currency (symbol, name) VALUES ('SOL', 'Solana') ON CONFLICT (symbol) DO NOTHING;
INSERT INTO crypto_currency (symbol, name) VALUES ('XRP', 'Ripple') ON CONFLICT (symbol) DO NOTHING;
INSERT INTO crypto_currency (symbol, name) VALUES ('ADA', 'Cardano') ON CONFLICT (symbol) DO NOTHING;
INSERT INTO crypto_currency (symbol, name) VALUES ('DOGE', 'Dogecoin') ON CONFLICT (symbol) DO NOTHING;
INSERT INTO crypto_currency (symbol, name) VALUES ('AVAX', 'Avalanche') ON CONFLICT (symbol) DO NOTHING;
INSERT INTO crypto_currency (symbol, name) VALUES ('NEAR', 'NEAR Protocol') ON CONFLICT (symbol) DO NOTHING;
INSERT INTO crypto_currency (symbol, name) VALUES ('LTC', 'Litecoin') ON CONFLICT (symbol) DO NOTHING;
