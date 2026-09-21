UPDATE `portal_entries`
SET `url` = REPLACE(`url`, 'https://github.com/huangke977-star/lingxi-portal', 'https://github.com/huangke977-star/hlovet-hub'),
    `updated_at` = CURRENT_TIMESTAMP(3)
WHERE `url` LIKE 'https://github.com/huangke977-star/lingxi-portal%';
