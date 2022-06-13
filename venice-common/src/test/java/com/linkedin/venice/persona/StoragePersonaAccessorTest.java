package com.linkedin.venice.persona;

import com.linkedin.venice.helix.HelixAdapterSerializer;
import com.linkedin.venice.helix.ZkClientFactory;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.ZkServerWrapper;
import java.util.HashSet;
import java.util.Set;
import org.apache.helix.zookeeper.impl.client.ZkClient;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;


public class StoragePersonaAccessorTest {

  private StoragePersonaAccessor accessor;
  private ZkClient zkClient;
  private ZkServerWrapper zk;

  String name = "testUser";
  long quotaNumber = 23;
  Set<String> storesToEnforce;
  Set<String> owners;

  @BeforeMethod
  public void setUp() {
    zk = ServiceFactory.getZkServer();
    zkClient = ZkClientFactory.newZkClient(zk.getAddress());
    accessor = new StoragePersonaAccessor("testStorageAccessor", new HelixAdapterSerializer(), zkClient);

    int numStores = 10;
    int numOwners = 10;

    storesToEnforce = new HashSet<>();
    for (int i = 0; i < numStores; i++) {
      storesToEnforce.add("testStore" + i);
    }

    owners = new HashSet<>();
    for (int i = 0; i < numOwners; i++) {
      owners.add("testOwner" + i);
    }
  }

  @AfterMethod
  public void cleanUp() {
    zkClient.close();
    zk.close();
  }

  @Test
  public void testCreatePersona() {
    accessor.createPersona(name, quotaNumber, storesToEnforce, owners);
    Persona persona = accessor.getPersonaFromZk(name);
    Assert.assertEquals(persona.getName(), name);
    Assert.assertEquals(persona.getQuotaNumber(), quotaNumber);
    Assert.assertEquals(persona.getStoresToEnforce(), storesToEnforce);
    Assert.assertEquals(persona.getOwners(), owners);
  }

  @Test
  public void testContainsPersona() {
    Assert.assertFalse(accessor.containsPersona(name));
    accessor.createPersona(name, quotaNumber, storesToEnforce, owners);
    Assert.assertTrue(accessor.containsPersona(name));
  }

  @Test
  public void testUpdatePersona() {
    accessor.createPersona(name, quotaNumber, storesToEnforce, owners);
    Persona persona = accessor.getPersonaFromZk(name);
    name = "newName";
    quotaNumber = 25;
    storesToEnforce.add("newStore");
    owners.add("newOwner");
    persona.setName(name);
    persona.setQuotaNumber(quotaNumber);
    persona.setStoresToEnforce(storesToEnforce);
    persona.setOwners(owners);
    accessor.updatePersona(persona);
    persona = accessor.getPersonaFromZk(name);
    Assert.assertEquals(persona.getName(), name);
    Assert.assertEquals(persona.getQuotaNumber(), quotaNumber);
    Assert.assertEquals(persona.getStoresToEnforce(), storesToEnforce);
    Assert.assertEquals(persona.getOwners(), owners);
  }

  @Test
  public void testDeletePersona() {
    accessor.createPersona(name, quotaNumber, storesToEnforce, owners);
    Persona persona = accessor.getPersonaFromZk(name);
    accessor.deletePersona(persona);
    Assert.assertFalse(accessor.containsPersona(name));
  }

}
