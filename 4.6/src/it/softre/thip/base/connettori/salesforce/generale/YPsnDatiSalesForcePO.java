package it.softre.thip.base.connettori.salesforce.generale;

import com.thera.thermfw.persist.*;
import java.sql.*;
import java.util.*;
import it.thera.thip.cs.*;
import com.thera.thermfw.common.*;
import it.thera.thip.base.azienda.Azienda;
import com.thera.thermfw.security.*;

public abstract class YPsnDatiSalesForcePO extends EntitaAzienda implements BusinessObject, Authorizable, Deletable, Conflictable {

	private static YPsnDatiSalesForce cInstance;

	protected String iInstanceUrl;

	protected String iToken;
	
	protected String iNomeListinoPrezzi;

	@SuppressWarnings("rawtypes")
	public static Vector retrieveList(String where, String orderBy, boolean optimistic) throws SQLException, ClassNotFoundException, InstantiationException, IllegalAccessException {
		if (cInstance == null)
			cInstance = (YPsnDatiSalesForce)Factory.createObject(YPsnDatiSalesForce.class);
		return PersistentObject.retrieveList(cInstance, where, orderBy, optimistic);
	}

	public static YPsnDatiSalesForce elementWithKey(String key, int lockType) throws SQLException {
		return (YPsnDatiSalesForce)PersistentObject.elementWithKey(YPsnDatiSalesForce.class, key, lockType);
	}
	
	public YPsnDatiSalesForcePO() {
		setIdAzienda(Azienda.getAziendaCorrente());
	}

	public void setInstanceUrl(String instanceUrl) {
		this.iInstanceUrl = instanceUrl;
		setDirty();
	}

	public String getInstanceUrl() {
		return iInstanceUrl;
	}

	public void setToken(String token) {
		this.iToken = token;
		setDirty();
	}
	
	public String getToken() {
		return iToken;
	}

	public String getNomeListinoPrezzi() {
		return iNomeListinoPrezzi;
	}

	public void setNomeListinoPrezzi(String iNomeListinoPrezzi) {
		this.iNomeListinoPrezzi = iNomeListinoPrezzi;
	}

	public void setIdAzienda(String idAzienda) {
		iAzienda.setKey(idAzienda);
		setDirty();
		setOnDB(false);
	}

	public String getIdAzienda() {
		String key = iAzienda.getKey();
		return key;
	}

	public void setEqual(Copyable obj) throws CopyException {
		super.setEqual(obj);
	}

	@SuppressWarnings("rawtypes")
	public Vector checkAll(BaseComponentsCollection components) {
		Vector errors = new Vector();
		components.runAllChecks(errors);
		return errors;
	}

	public void setKey(String key) {
		setIdAzienda(key);
	}

	public String getKey() {
		return getIdAzienda();
	}

	public boolean isDeletable() {
		return checkDelete() == null;
	}

	public String toString() {
		return getClass().getName() + " [" + KeyHelper.formatKeyString(getKey()) + "]";
	}

	protected TableManager getTableManager() throws SQLException {
		return YPsnDatiSalesForceTM.getInstance();
	}

}

