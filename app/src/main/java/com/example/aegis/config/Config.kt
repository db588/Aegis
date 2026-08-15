package com.example.aegis.config

/**
 * Configuration constants for DNS Blocker app
 * Modify these values to customize app behavior
 */
object Config {
    
    // ========== DNS Configuration ==========
    
    /**
     * Primary upstream DNS server
     * Options:
     * - 8.8.8.8 (Google, fast and reliable)
     * - 1.1.1.1 (Cloudflare, privacy-focused)
     * - 9.9.9.9 (Quad9, security-focused)
     * - 208.67.222.222 (OpenDNS, blocks malware)
     */
    const val PRIMARY_DNS = "8.8.8.8"
    
    /**
     * Secondary/fallback DNS server
     */
    const val SECONDARY_DNS = "8.8.4.4"
    
    /**
     * DNS query timeout in milliseconds
     */
    const val DNS_TIMEOUT_MS = 5000
    
    // ========== VPN Configuration ==========
    
    /**
     * VPN session name shown in status bar
     */
    const val VPN_SESSION_NAME = "Aegis"
    
    /**
     * MTU size for VPN packets
     */
    const val VPN_MTU = 1500
    
    /**
     * Enable/disable VPN logging
     */
    const val ENABLE_VPN_LOGGING = false
    
    // ========== Blocklist Configuration ==========
    
    /**
     * Enable automatic blocklist updates
     * (Future feature)
     */
    const val AUTO_UPDATE_BLOCKLISTS = false
    
    /**
     * Update interval in hours
     */
    const val UPDATE_INTERVAL_HOURS = 24
    
    /**
     * Maximum domains per blocklist
     * Set to 0 for unlimited
     */
    const val MAX_DOMAINS_PER_LIST = 0
    
    // ========== Social Media Domains ==========
    
    /**
     * Default social media domains to block
     */
    val SOCIAL_MEDIA_DOMAINS = listOf(
        // X / Twitter
        "x.com",
        "twitter.com",
        "t.co",
        
        // Reddit
        "reddit.com",
        "redd.it",
        
        // Meta (Facebook, Instagram, WhatsApp)
        "facebook.com",
        "fb.com",
        "instagram.com",
        "whatsapp.com",
        
        // TikTok
        "tiktok.com",
        "douyin.com",
        
        // Messaging
        "discord.gg",
        "discord.com",
        "telegram.org",
        "telegram.me",
        "snapchat.com",
        
        // LinkedIn
        "linkedin.com",
        
        // Threads
        "threads.net",
        
        // YouTube (optional - uncomment to block)
        // "youtube.com",
        // "youtu.be"
    )
    
    // ========== Database Configuration ==========
    
    /**
     * Database name
     */
    const val DATABASE_NAME = "aegis_database"
    
    /**
     * Enable database encryption (future feature)
     */
    const val ENCRYPT_DATABASE = false
    
    // ========== Performance Tuning ==========
    
    /**
     * Cache size for DNS responses (number of domains)
     */
    const val DNS_CACHE_SIZE = 1000
    
    /**
     * DNS cache timeout in seconds
     */
    const val DNS_CACHE_TTL = 3600
    
    /**
     * Number of threads for DNS processing
     */
    const val DNS_THREAD_POOL_SIZE = 2
    
    // ========== Logging & Debug ==========
    
    /**
     * Enable debug logging to logcat
     */
    const val DEBUG_MODE = false
    
    /**
     * Log blocked domains (very verbose)
     */
    const val LOG_BLOCKED_DOMAINS = false
    
    /**
     * Log DNS queries
     */
    const val LOG_DNS_QUERIES = false
}
