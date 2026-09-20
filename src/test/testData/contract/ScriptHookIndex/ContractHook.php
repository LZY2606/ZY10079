<?php declare(strict_types=1);

namespace Contract\Script;

use Shopware\Core\Framework\Script\Execution\Hook;

class ContractHook extends Hook
{
    public const HOOK_NAME = 'contract.hook';

    public static function getServiceIds(): array
    {
        return [];
    }
}
