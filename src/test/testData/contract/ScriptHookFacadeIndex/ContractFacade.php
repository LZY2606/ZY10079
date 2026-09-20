<?php declare(strict_types=1);

namespace Contract\Script;

use Shopware\Core\Framework\Script\Execution\Awareness\HookServiceFactory;

class ContractFacade extends HookServiceFactory
{
    public function getName(): string
    {
        return 'contract.facade';
    }

    public function factory(): ContractFacadeFactory
    {
    }
}
