<?php declare(strict_types=1);

namespace SwagContract\Script;

use Shopware\Core\Framework\Script\Execution\Hook;

class ContractHook extends Hook
{
    public const HOOK_NAME = 'contract.hook';

    public function getServiceIds(): array
    {
        return [];
    }
}
