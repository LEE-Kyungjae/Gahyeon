CREATE TABLE news_article (
    id VARCHAR(36) PRIMARY KEY,
    canonical_url VARCHAR(2048) NOT NULL UNIQUE,
    source_id VARCHAR(100) NOT NULL,
    source_name VARCHAR(200) NOT NULL,
    source_domain VARCHAR(255) NOT NULL,
    official BOOLEAN NOT NULL,
    trust_tier INTEGER NOT NULL,
    title VARCHAR(1000) NOT NULL,
    summary VARCHAR(4000),
    published_at TIMESTAMP WITH TIME ZONE NOT NULL,
    discovered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    event_fingerprint VARCHAR(64) NOT NULL
);

CREATE INDEX idx_news_article_published_at ON news_article (published_at);
CREATE INDEX idx_news_article_event_fingerprint ON news_article (event_fingerprint);
