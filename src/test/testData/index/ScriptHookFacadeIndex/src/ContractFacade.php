<?php declare(strict_types=1);

namespace SwagContract\Script;

use Shopware\Core\Framework\Script\Execution\Awareness\HookServiceFactory;

class ContractFacade extends HookServiceFactory
{
    public function getName(): string
    {
        return 'contract_facade';
    }

    public function factory(): \SwagContract\Script\ContractService
    {
        return new \SwagContract\Script\ContractService();
    }
}
