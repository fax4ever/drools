/*
 * Copyright 2023 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.reliability.infinispan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.conf.PersistedSessionOption;
import org.kie.api.runtime.rule.FactHandle;
import org.test.domain.Person;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(BeforeAllMethodExtension.class)
class ReliabilityTest extends ReliabilityTestBasics {

    private static final String BASIC_RULE =
            "import " + Person.class.getCanonicalName() + ";" +
            "global java.util.List results;" +
            "rule X when\n" +
            "  $s: String()\n" +
            "  $p: Person( getName().startsWith($s) )\n" +
            "then\n" +
            "  results.add( $p.getName() );\n" +
            "end";

    @Test
    void createAndUseOfNonReliableSession_shouldWorkNormally() {
        createSession(BASIC_RULE, null);

        insert("M");
        insertMatchingPerson("Matching Person One", 37);

        insertNonMatchingPerson("Toshiya", 35);
        insertMatchingPerson("Matching Person Two", 40);

        fireAllRules();

        assertThat(getResults()).containsExactlyInAnyOrder("Matching Person One", "Matching Person Two");
    }

    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithExplicitSafepoints") // FULL fails with "ReliablePropagationList; no valid constructor"
    void insertFailoverInsertFire_shouldRecoverFromFailover(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy) {
        createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        insert("M");
		insertMatchingPerson("Matching Person One", 37);

        //-- Assume JVM down here. Fail-over to other JVM or rebooted JVM
        //-- ksession and kbase are lost. CacheManager is recreated. Client knows only "id"
        failover();

        restoreSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

		insertNonMatchingPerson("Toshiya", 35);
        insertMatchingPerson("Matching Person Two", 40);

		fireAllRules();

		assertThat(getResults()).containsExactlyInAnyOrder("Matching Person One", "Matching Person Two");
    }


    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithExplicitSafepoints") // With Remote, FULL fails with "ReliablePropagationList; no valid constructor" even without failover
    void noFailover(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy) {

        createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        insert("M");
		insertMatchingPerson("Matching Person One", 37);

        if (safepointStrategy == PersistedSessionOption.SafepointStrategy.EXPLICIT) {
            safepoint();
        }
        restoreSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

		insertNonMatchingPerson("Toshiya", 41);
		insertMatchingPerson("Matching Person Two", 40);

        fireAllRules();

		assertThat(getResults()).containsExactlyInAnyOrder("Matching Person One", "Matching Person Two");
    }


    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithExplicitSafepoints") // FULL fails with "ReliablePropagationList; no valid constructor"
    void insertFireInsertFailoverInsertFire_shouldMatchFactInsertedBeforeFailover(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy) {

        createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        insert("M");
		insertMatchingPerson("Matching Person One", 37);

		fireAllRules();

        insertMatchingPerson("Matching Person Two", 40);

        failover();

        restoreSession(BASIC_RULE, persistenceStrategy, safepointStrategy);
        clearResults();

        insertNonMatchingPerson("Toshiya", 35);
        insertMatchingPerson("Matching Person Three", 41);

        fireAllRules();

        assertThat(getResults()).containsExactlyInAnyOrder("Matching Person Two", "Matching Person Three");
    }

    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithAllSafepoints") // FULL fails with "ReliablePropagationList; no valid constructor"
    void insertFireFailoverInsertFire_shouldNotRepeatFiredMatch(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy) {
        createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        insert("M");
		insertMatchingPerson("Matching Person One", 37);

		fireAllRules();

        failover();

        restoreSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        insertNonMatchingPerson("Toshiya", 35);
		insertMatchingPerson("Matching Person Two", 40);

		fireAllRules();

		assertThat(getResults()).containsExactlyInAnyOrder("Matching Person One", "Matching Person Two");
    }

    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithExplicitSafepoints") // FULL fails with "ReliablePropagationList; no valid constructor"
    void updateBeforeFailover_shouldRecoverFromFailover(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy) {

        createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        insert("M");
        Person p1 = new Person("Mario", 49);
        FactHandle fh1 = insert(p1);
        Person p2 = new Person("Toshiya", 45);
        FactHandle fh2 = insert(p2);

        assertThat(fireAllRules()).isEqualTo(1);
        assertThat(getResults()).containsExactlyInAnyOrder("Mario");

        p1.setName("SuperMario");
        update(fh1, p1);
        p2.setName("MegaToshiya");
        update(fh2, p2);

        failover();
        restoreSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        assertThat(fireAllRules()).isEqualTo(1);
        assertThat(getResults()).containsExactlyInAnyOrder("Mario", "MegaToshiya");

        failover();
        restoreSession(BASIC_RULE, persistenceStrategy, safepointStrategy);
        clearResults();

        assertThat(fireAllRules()).isZero();
        assertThat(getResults()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithExplicitSafepoints") // FULL fails with "ReliablePropagationList; no valid constructor"
    void deleteBeforeFailover_shouldRecoverFromFailover(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy) {

        createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        FactHandle fhString = insert("M");
        insertMatchingPerson("Matching Person One",37);
        insertNonMatchingPerson("Toshiya",35);

        assertThat(fireAllRules()).isEqualTo(1);
        assertThat(getResults()).containsExactlyInAnyOrder("Matching Person One");

        delete(fhString);

        failover();
        restoreSession(BASIC_RULE, persistenceStrategy, safepointStrategy);
        clearResults();

        insertMatchingPerson("Matching Person Two",40);

        assertThat(fireAllRules()).isZero();
        assertThat(getResults()).isEmpty();

        insert("T");

        failover();
        restoreSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        assertThat(fireAllRules()).isEqualTo(1);
        assertThat(getResults()).containsExactlyInAnyOrder("Toshiya");
    }

    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithExplicitSafepoints")
    void deleteAfterFailover_shouldNotMatch(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy){
        createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        insert("M");
        Person pMike = new Person("Mike", 23);
        insert(pMike);

        failover();
        restoreSession(BASIC_RULE, persistenceStrategy, safepointStrategy);
        clearResults();

        Optional<FactHandle> getFactHandleForPerson = getFactHandleForPerson(pMike);
        if (!getFactHandleForPerson.isEmpty()){
            delete(getFactHandleForPerson.get());
        }

        assertThat(fireAllRules()).isEqualTo(0);
    }

    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithExplicitSafepoints")
    void deleteBeforeFailover_shouldNotMatch(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy){
        createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        insert("M");
        Person pMike = new Person("Mike", 23);
        FactHandle fhMike = insert(pMike);

        delete(fhMike);

        failover();
        restoreSession(BASIC_RULE, persistenceStrategy, safepointStrategy);
        clearResults();

        assertThat(fireAllRules()).isEqualTo(0);
    }

    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithExplicitSafepoints")
    void updateByObjectBeforeFailover_shouldMatchUpdatedFact(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy){

        createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        insert("M");
        insertMatchingPerson("Mark", 37);
        FactHandle fhNicole = insertNonMatchingPerson("Nicole", 32);

        assertThat(fireAllRules()).isEqualTo(1);

        updateWithMatchingPerson(fhNicole,new Person("Mary", 32));

        failover();

        KieSession session = restoreSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        assertThat(fireAllRules()).isEqualTo(1);

        failover();

        assertThat(session.fireAllRules()).isZero();
    }

    @ParameterizedTest
    @MethodSource("strategyProviderFull")
    void insertFailover_propListShouldNotBeEmpty(PersistedSessionOption.PersistenceStrategy strategy){
        createSession(BASIC_RULE, strategy);

        insert("M");
        insertMatchingPerson("Maria", 30);

        failover();

        restoreSession(BASIC_RULE, strategy);


        assertThat(fireAllRules()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("strategyProviderFull")
    void insertFireFailover_shouldNotRepeatFiredMatch(PersistedSessionOption.PersistenceStrategy strategy){
        createSession(BASIC_RULE, strategy);

        insert("M");
        insertMatchingPerson("Maria", 30);

        fireAllRules();

        failover();

        restoreSession(BASIC_RULE, strategy);

        assertThat(fireAllRules()).isZero();
    }

    @ParameterizedTest
    @MethodSource("strategyProviderFull")
    void insertUpdateFailover_shouldNotFiredMatch(PersistedSessionOption.PersistenceStrategy strategy){
        createSession(BASIC_RULE, strategy);

        insert("M");
        FactHandle fhMaria = insertMatchingPerson("Maria", 30);

        updateWithNonMatchingPerson(fhMaria, new Person("Nicole", 32));

        failover();

        restoreSession(BASIC_RULE, strategy);

        assertThat(fireAllRules()).isZero();
    }

    @ParameterizedTest
    @MethodSource("strategyProviderFull")
    void insertNonMatching_Failover_UpdateWithMatching_ShouldFiredMatch(PersistedSessionOption.PersistenceStrategy strategy) {
        createSession(BASIC_RULE, strategy);

        insert("N");
        FactHandle fhMaria = insertMatchingPerson("Maria", 30);

        failover();

        restoreSession(BASIC_RULE, strategy);

        updateWithMatchingPerson(fhMaria, new Person("Nicole",32));

        assertThat(fireAllRules()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithExplicitSafepoints")
    void multipleKieSessions_BasicTest(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy) {
        KieSession session1 = createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);
        KieSession session2 = createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        insert(session1, "M");
        insert(session2, "N");

        insert(session1, new Person("Mike-session1",27)); // insert matching person
        insert(session2, new Person("Mary-session2",34)); // insert non matching person

        assertThat(fireAllRules(session1)).isEqualTo(1);
        assertThat(fireAllRules(session2)).isEqualTo(0);

        failover();

        session1 = restoreSession(session1.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);
        session2 = restoreSession(session2.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);

        // clear results
        clearResults(session1);
        clearResults(session2);

        insert(session1, new Person("Michael-session1",42)); // insert matching person
        insert(session2, new Person("Nancy-session2",25)); // insert matching person

        assertThat(fireAllRules(session1)).isEqualTo(1);
        assertThat(fireAllRules(session2)).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithExplicitSafepoints") // FULL fails with "ReliablePropagationList; no valid constructor"
    void multipleKieSessions_insertFailoverInsertFire_shouldRecoverFromFailover(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy) {
        KieSession session1 = createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);
        KieSession session2 = createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        insert(session1,"M");
        insertMatchingPerson(session1, "Mike", 37);
        insert(session2,"N");
        insertNonMatchingPerson(session2,"Helen",33);

        failover();

        session1 = restoreSession(session1.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);
        session2 = restoreSession(session2.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);

        insertNonMatchingPerson(session1,"Toshiya", 35);
        insertMatchingPerson(session2,"Nicole", 40);

        assertThat(fireAllRules(session1)).isEqualTo(1);
        assertThat(fireAllRules(session2)).isEqualTo(1);

        assertThat(getResults(session1)).containsExactlyInAnyOrder("Mike");
        assertThat(getResults(session2)).containsExactlyInAnyOrder("Nicole");
    }

    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithExplicitSafepoints") // With Remote, FULL fails with "ReliablePropagationList; no valid constructor" even without failover
    void multipleKieSessions_noFailover(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy) {

        KieSession session1 = createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);
        KieSession session2 = createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        insert(session1,"M");
        insertMatchingPerson(session1,"Matching Person One", 37);
        insert(session2, new Person("Mary",32));

        if (safepointStrategy == PersistedSessionOption.SafepointStrategy.EXPLICIT) {
            safepoint();
        }

        session1 = restoreSession(session1.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);
        session2 = restoreSession(session2.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);

        insertNonMatchingPerson(session1,"Toshiya", 41);
        insertMatchingPerson(session1,"Matching Person Two", 40);
        insert(session2, "H");
        insert(session2,new Person("Helen",43));

        fireAllRules(session1);
        assertThat(getResults(session1)).containsExactlyInAnyOrder("Matching Person One", "Matching Person Two");

        assertThat(fireAllRules(session2)).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithExplicitSafepoints") // FULL fails with "ReliablePropagationList; no valid constructor"
    void multipleKieSessions_insertFireInsertFailoverInsertFire_shouldMatchFactInsertedBeforeFailover(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy) {

        KieSession session1 = createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);
        KieSession session2 = createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        insert(session1,"M");
        insertMatchingPerson(session1,"Matching Person One", 37);
        insert(session2, "N");
        insertMatchingPerson(session2, "Nicole",34);

        fireAllRules(session1);
        fireAllRules(session2);

        insertMatchingPerson(session1,"Matching Person Two", 40);
        insertMatchingPerson(session2, "Nancy",23);

        failover();

        session1 = restoreSession(session1.getIdentifier(),BASIC_RULE, persistenceStrategy, safepointStrategy);
        session2 = restoreSession(session2.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);

        clearResults(session1);
        clearResults(session2);

        insertNonMatchingPerson(session1,"Nora", 35);
        insertMatchingPerson(session1,"Matching Person Three", 41);
        insertNonMatchingPerson(session2,"Mike", 35);
        insertMatchingPerson(session2,"Noah", 41);

        fireAllRules(session1);
        fireAllRules(session2);

        assertThat(getResults(session1)).containsExactlyInAnyOrder("Matching Person Two", "Matching Person Three");
        assertThat(getResults(session2)).containsExactlyInAnyOrder("Nancy", "Noah");
    }

    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithExplicitSafepoints") // FULL fails with "ReliablePropagationList; no valid constructor"
    void multipleKieSessions_updateBeforeFailover_shouldRecoverFromFailover(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy) {
        KieSession session1 = createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);
        KieSession session2 = createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        insert(session1,"M");
        Person p1 = new Person("Mario", 49);
        FactHandle fh1 = insert(session1,p1);
        Person p2 = new Person("Toshiya", 45);
        FactHandle fh2 = insert(session1, p2);
        insert(session2,new Person("Toshiya", 45));

        assertThat(fireAllRules(session1)).isEqualTo(1);
        assertThat(getResults(session1)).containsExactlyInAnyOrder("Mario");
        assertThat(fireAllRules(session2)).isEqualTo(0);

        insert(session2, "T");
        p1.setName("SuperMario");
        update(session1, fh1, p1);
        p2.setName("MegaToshiya");
        update(session1, fh2, p2);

        failover();
        session1 = restoreSession(session1.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);
        session2 = restoreSession(session2.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);

        assertThat(fireAllRules(session1)).isEqualTo(1);
        assertThat(getResults(session1)).containsExactlyInAnyOrder("Mario", "MegaToshiya");
        assertThat(fireAllRules(session2)).isEqualTo(1);
        assertThat(getResults(session2)).containsExactlyInAnyOrder("Toshiya");

        failover();
        session1 = restoreSession(session1.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);
        session2 = restoreSession(session2.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);
        clearResults(session1);
        clearResults(session2);

        assertThat(fireAllRules(session1)).isZero();
        assertThat(getResults(session1)).isEmpty();

        assertThat(fireAllRules(session2)).isZero();
        assertThat(getResults(session2)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("strategyProviderStoresOnlyWithExplicitSafepoints") // FULL fails with "ReliablePropagationList; no valid constructor"
    void multipleKieSessions_deleteBeforeFailover_shouldRecoverFromFailover(PersistedSessionOption.PersistenceStrategy persistenceStrategy, PersistedSessionOption.SafepointStrategy safepointStrategy) {

        KieSession session1 = createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);
        KieSession session2 = createSession(BASIC_RULE, persistenceStrategy, safepointStrategy);

        FactHandle fhStringM = insert(session1,"M");
        insertMatchingPerson(session1,"Matching Person One",37);
        insertNonMatchingPerson(session1,"Toshiya",37);
        insert(session2,"N");
        FactHandle fhN = insertMatchingPerson(session2,"Nicole",35);

        assertThat(fireAllRules(session1)).isEqualTo(1);
        assertThat(getResults(session1)).containsExactlyInAnyOrder("Matching Person One");

        delete(session1,fhStringM);
        delete(session2, fhN);

        failover();

        session1 = restoreSession(session1.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);
        session2 = restoreSession(session2.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);
        clearResults(session1);
        clearResults(session2);

        insertMatchingPerson(session1,"Matching Person Two",40);
        insertMatchingPerson(session2,"Nancy", 25);

        assertThat(fireAllRules(session1)).isZero();
        assertThat(getResults(session1)).isEmpty();
        assertThat(fireAllRules(session2)).isEqualTo(1);
        assertThat(getResults(session2)).containsExactlyInAnyOrder("Nancy");

        insert(session1,"T");

        failover();
        session1 = restoreSession(session1.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);
        session2 = restoreSession(session2.getIdentifier(), BASIC_RULE, persistenceStrategy, safepointStrategy);

        assertThat(fireAllRules(session1)).isEqualTo(1);
        assertThat(getResults(session1)).containsExactlyInAnyOrder("Toshiya");
    }


}
