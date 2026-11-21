#!/usr/bin/env python3
"""Offline structure audit only. NOT a substitute for mvn verify or integration tests."""
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parent.parent
poms = [root / 'pom.xml', *root.glob('*/pom.xml')]
for p in poms:
    ET.parse(p)
for file in [
    'contracts/src/main/proto/inventory.proto',
    'node-server/src/main/resources/db/migration/V1__initial_schema.sql',
    'node-server/src/main/java/edu/inventory/node/grpc/GrpcServerLifecycle.java',
    'deploy/docker-compose.yml',
    'docs/01-requirements.md', 'docs/02-architecture.md',
    'docs/03-protocol.md', 'docs/04-recovery.md',
    'docs/05-test-plan.md', 'docs/06-handover.md',
]:
    assert (root / file).is_file(), f'Missing: {file}'
print(f'PASS: XML syntax for {len(poms)} POMs and required files exist')
print('WARNING: this audit does not compile Java, run Maven, or run PostgreSQL')
