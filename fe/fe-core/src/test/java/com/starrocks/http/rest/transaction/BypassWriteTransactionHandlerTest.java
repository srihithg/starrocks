// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.http.rest.transaction;

import com.starrocks.common.StarRocksException;
import com.starrocks.transaction.TransactionState.LoadJobSourceType;
import com.starrocks.transaction.TransactionStateSnapshot;
import com.starrocks.transaction.TransactionStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BypassWriteTransactionHandlerTest {

    // The live path rejects a label whose transaction was not created by BYPASS_WRITE. Once the full
    // state is count-evicted, the terminal-state cache still carries the source type, so the same
    // request must not instead succeed off a foreign cached outcome.
    @Test
    public void testAssertCachedOutcomeRejectsNonBypassSource() {
        TransactionStateSnapshot foreign = new TransactionStateSnapshot(
                TransactionStatus.VISIBLE, null, LoadJobSourceType.BACKEND_STREAMING);
        StarRocksException e = Assertions.assertThrows(StarRocksException.class,
                () -> BypassWriteTransactionHandler.assertCachedOutcomeIsBypassWrite(foreign, "L"));
        Assertions.assertTrue(e.getMessage().contains("isn't created in " + LoadJobSourceType.BYPASS_WRITE.name()),
                e.getMessage());
    }

    // A null/unknown source is fail-closed: treated as "not bypass write" and rejected, matching the
    // live path's strict guard.
    @Test
    public void testAssertCachedOutcomeRejectsNullSource() {
        TransactionStateSnapshot untyped = new TransactionStateSnapshot(TransactionStatus.VISIBLE, null, null);
        Assertions.assertThrows(StarRocksException.class,
                () -> BypassWriteTransactionHandler.assertCachedOutcomeIsBypassWrite(untyped, "L"));
    }

    // A genuine bypass-write cached outcome passes the guard.
    @Test
    public void testAssertCachedOutcomeAllowsBypassWrite() throws StarRocksException {
        TransactionStateSnapshot bypass = new TransactionStateSnapshot(
                TransactionStatus.VISIBLE, null, LoadJobSourceType.BYPASS_WRITE);
        BypassWriteTransactionHandler.assertCachedOutcomeIsBypassWrite(bypass, "L"); // no throw
    }

    // UNKNOWN status means the cache holds no outcome for the label; the guard must NOT fire, leaving the
    // caller's not-found branch to handle it (a probe for an unseen label must not become a source error).
    @Test
    public void testAssertCachedOutcomeUnknownFallsThrough() throws StarRocksException {
        TransactionStateSnapshot unknown = new TransactionStateSnapshot(TransactionStatus.UNKNOWN, null);
        BypassWriteTransactionHandler.assertCachedOutcomeIsBypassWrite(unknown, "L"); // no throw
    }

    // A non-terminal, non-UNKNOWN status (e.g. PREPARED, which the evicted path does not answer from and
    // routes to its not-actionable branch) must fall through, regardless of source. Gating only on UNKNOWN
    // would wrongly reject here and change the not-found message; the guard fires only on a real terminal
    // outcome (VISIBLE/COMMITTED/ABORTED).
    @Test
    public void testAssertCachedOutcomeNonTerminalFallsThrough() throws StarRocksException {
        TransactionStateSnapshot prepared = new TransactionStateSnapshot(
                TransactionStatus.PREPARED, null, LoadJobSourceType.BACKEND_STREAMING);
        BypassWriteTransactionHandler.assertCachedOutcomeIsBypassWrite(prepared, "L"); // no throw
    }
}
