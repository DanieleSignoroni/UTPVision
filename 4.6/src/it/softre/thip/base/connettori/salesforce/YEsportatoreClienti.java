package it.softre.thip.base.connettori.salesforce;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.MediaType;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.gson.JsonObject;
import com.thera.thermfw.batch.BatchRunnable;
import com.thera.thermfw.persist.ConnectionManager;
import com.thera.thermfw.persist.Factory;
import com.thera.thermfw.persist.PersistentObject;

import it.softre.thip.base.connettori.salesforce.generale.YPsnDatiSalesForce;
import it.softre.thip.base.connettori.salesforce.tabelle.YClientiInseriti;
import it.softre.thip.base.connettori.salesforce.tabelle.YReferentiInseriti;
import it.softre.thip.base.connettori.utils.YApiManagement;
import it.thera.thip.api.client.ApiResponse;
import it.softre.thip.base.connettori.api.YApiRequest.Method;
import it.thera.thip.base.azienda.Azienda;
import it.thera.thip.base.cliente.ClienteVendita;
import it.thera.thip.base.cliente.ClienteVenditaTM;
import it.thera.thip.base.partner.Rubrica;
import it.thera.thip.base.partner.RubricaEstesa;
import it.thera.thip.cs.DatiComuniEstesi;

public class YEsportatoreClienti extends BatchRunnable{

	public String endpoint = null;

	public String id = "Account";

	public String apiPath = null;

	@Override
	protected boolean run() {
		boolean ret = true;
		writeLog("*** ESPORT CLIENTI - ACCOUNT SALES FORCE ***");
		YPsnDatiSalesForce psnDati = YPsnDatiSalesForce.getCurrentPersDatiSalesForce(Azienda.getAziendaCorrente());
		if(psnDati != null) {
			endpoint = psnDati.getInstanceUrl() + "/sobjects/";
			apiPath = psnDati.getToken();
			List<ClienteVendita> prodotti = getListaClientiValidi();
			for (Iterator<ClienteVendita> iterator = prodotti.iterator(); iterator.hasNext();) {
				writeLog("");
				try {
					ClienteVendita cliente = (ClienteVendita) iterator.next();
					writeLog("--------- Processo l'cliente : "+cliente.getKey()+" -------------");
					String pIVA = cliente.getPartitaIVA();
					if(pIVA != null) {
						pIVA = pIVA.replace(" ", "+");
						String json = getJSONAdd(cliente);
						String accountId = accountDaIVA(pIVA);
						if(accountId == null) {
							writeLog("L'cliente non e' ancora stato esportato in Sales Force, procedo all'inserimento");
							ApiResponse response = YApiManagement.callApi(endpoint+id, Method.POST, MediaType.APPLICATION_JSON, null, null, json,apiPath);
							if(response.success()) {
								String respKey = (String) response.getBodyAsJSONObject().get("id");
								ApiResponse read = YApiManagement.callApi(endpoint+id+"/"+respKey, Method.GET, MediaType.APPLICATION_JSON, null, null, null,apiPath);
								if(read.success()) {
									writeLog("cliente inserito in Sales Force correttamente");
									YClientiInseriti ins = (YClientiInseriti) Factory.createObject(YClientiInseriti.class);
									ins.setKey(cliente.getKey());
									ins.setIdSalesForce(respKey);
									if(ins.save() >= 0) {
										writeLog("Popolata correttamente la tabella di appoggio : "+ins.getAbstractTableManager().getMainTableName());
										ConnectionManager.commit();
									}else {
										writeLog("Vi sono stati errori nella popolazione della tabella di appoggio : "+ins.getAbstractTableManager().getMainTableName());
										ConnectionManager.rollback();
									}
								}
								esportaReferenti(cliente,respKey,true);
							}else {
								writeLog("Impossibile inserire l'cliente, errori: \n"+response.getBodyAsString());
							}
						}else {
							YClientiInseriti tab = getClienteInseritoByKey(cliente.getKey());
							ApiResponse read = YApiManagement.callApi(endpoint+id+"/"+accountId, Method.GET, MediaType.APPLICATION_JSON, null, null, null,apiPath);
							if(read.success()) {
								writeLog("L'cliente e' gia presente in Sales Force \n id = "+accountId+", procedo con l'aggiornamento");
								String command = "curl -X PATCH "+endpoint+id+"/"+accountId+"  -H \"Content-Type: application/json\"     -H \"Authorization: Bearer "+this.apiPath+"\" ";
								String formattedJson = json.replace("\"", "\"\"\"");
								command += " -d "+formattedJson+" ";
								Process powerShellProcess = Runtime.getRuntime().exec(command);
								powerShellProcess.getOutputStream().close();
								int exitValue = -1;
								try {
									exitValue = powerShellProcess.waitFor();
								} catch (InterruptedException e) {
									e.printStackTrace();
								}
								if(exitValue == 0) {
									writeLog("Account aggiornato correttamente");
									if(tab == null) {
										tab  = (YClientiInseriti) Factory.createObject(YClientiInseriti.class);
										tab.setKey(cliente.getKey());
										tab.setIdSalesForce(accountId);
									}
									if(tab.save() >= 0) {
										writeLog("Aggiornata correttamente la tabella di appoggio : "+tab.getAbstractTableManager().getMainTableName());
										ConnectionManager.commit();
									}else {
										writeLog("Vi sono stati errori nell'aggiornamento della tabella di appoggio : "+tab.getAbstractTableManager().getMainTableName());
										ConnectionManager.rollback();
									}
								}else {
									writeLog("Account aggiornato con errori");
								}
								esportaReferenti(cliente,tab.getIdSalesForce(),false);
							}else {
								if(tab != null) {
									//cancello il record in tab ins
									int rcDel = tab.delete();
									if(rcDel > 0) {
										ConnectionManager.commit();
									}else{
										ConnectionManager.rollback();
									}
								}
							}
						}
					}else {
						writeLog("La partita IVA e' un campo OBBLIGATORIO per poter esportare il cliente verso Sales Force...valorizzarlo");
					}
					writeLog("--------- Ho finito di processare l'cliente : "+cliente.getKey()+" -------------");
				} catch (JSONException e) {
					ret = false;
					e.printStackTrace();
				} catch (SQLException e) {
					ret = false;
					e.printStackTrace();
				} 
				catch (IOException e) {
					ret = false;
					e.printStackTrace();
				}
			}
		}
		return ret;
	}

	protected String accountDaIVA(String pIVA) {
		Map<String,String> parameters = new HashMap<String, String>();
		String query = "SELECT+Id+FROM+"+YFunzioni.ACCOUNT+"+WHERE+VAT_Number__c='"+pIVA+"'";
		parameters.put("q", query);
		ApiResponse resp = YApiManagement.callApi(endpoint.replace("sobjects", "query"), Method.GET, MediaType.APPLICATION_JSON, null, parameters, null,apiPath);
		if(resp.success()) {
			try {	
				JSONObject body = resp.getBodyAsJSONObject();
				JSONArray records = (JSONArray) body.get("records");
				for (int i = 0; i < records.length(); i++) {
					JSONObject obj = records.getJSONObject(i);
					if(obj.get("Id") != null) {
						return obj.getString("Id");
					}else {

					}
				}
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}else {
			writeLog(resp.getBodyAsString());
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	protected void esportaReferenti(ClienteVendita cliente, String respKey, boolean isInsert) {
		Iterator<Rubrica> iterRubriche = cliente.getAnagrafico().getRubriche().iterator();
		while (iterRubriche.hasNext()) {
			try {
				Rubrica rub = (Rubrica) iterRubriche.next();
				RubricaEstesa rubrica = (RubricaEstesa) RubricaEstesa.elementWithKey(RubricaEstesa.class, rub.getKey(), PersistentObject.NO_LOCK);
				if(rubrica != null) {
					String json = getJsonReferenteDaRubrica(rubrica, respKey);
					YReferentiInseriti tab = getReferenteInseritoByKey(rubrica.getKey());
					if(tab == null) {
						writeLog("Il referente non e' ancora stato esportato in Sales Force, procedo all'inserimento");
						ApiResponse response = YApiManagement.callApi(endpoint+"Contact", Method.POST, MediaType.APPLICATION_JSON, null, null, json,apiPath);
						if(response.success()) {
							String referenteId = (String) response.getBodyAsJSONObject().get("id");
							ApiResponse read = YApiManagement.callApi(endpoint+"Contact"+"/"+referenteId, Method.GET, MediaType.APPLICATION_JSON, null, null, null,apiPath);
							if(read.success()) {
								writeLog("Referente inserito in Sales Force correttamente");
								YReferentiInseriti ins = (YReferentiInseriti) Factory.createObject(YReferentiInseriti.class);
								ins.setKey(rubrica.getKey());
								ins.setIdSalesForce(referenteId);
								if(ins.save() >= 0) {
									writeLog("Popolata correttamente la tabella di appoggio : "+ins.getAbstractTableManager().getMainTableName());
									ConnectionManager.commit();
								}else {
									writeLog("Vi sono stati errori nella popolazione della tabella di appoggio : "+ins.getAbstractTableManager().getMainTableName());
									ConnectionManager.rollback();
								}
							}
						}else {
							writeLog("Impossibile inserire il referente, errori: \n"+response.getBodyAsString());
						}
					}else {
						ApiResponse read = YApiManagement.callApi(endpoint+"Contact"+"/"+tab.getIdSalesForce(), Method.DELETE, MediaType.APPLICATION_JSON, null, null, null,apiPath);
						if(read.success()) {
							writeLog("Il referente e' gia presente in Sales Force \n id = "+tab.getIdSalesForce()+", procedo con l'aggiornamento");
							String command = "curl -X PATCH "+endpoint+"Contact"+"/"+tab.getIdSalesForce()+"  -H \"Content-Type: application/json\"     -H \"Authorization: Bearer "+this.apiPath+"\" ";
							String formattedJson = json.replace("\"", "\"\"\"");
							command += " -d "+formattedJson+" ";
							Process powerShellProcess = Runtime.getRuntime().exec(command);
							powerShellProcess.getOutputStream().close();
							int exitValue = -1;
							try {
								exitValue = powerShellProcess.waitFor();
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
							if(exitValue == 0) {
								writeLog("Referente aggiornato correttamente");
								if(tab.save() >= 0) {
									writeLog("Aggiornata correttamente la tabella di appoggio : "+tab.getAbstractTableManager().getMainTableName());
									ConnectionManager.commit();
								}else {
									writeLog("Vi sono stati errori nell'aggiornamento della tabella di appoggio : "+tab.getAbstractTableManager().getMainTableName());
									ConnectionManager.rollback();
								}
							}else {
								writeLog("Referente aggiornato con errori");
							}
						}
					}
				}
			}catch (SQLException e) {
				e.printStackTrace();
			} catch (JSONException e) {
				e.printStackTrace();
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}

	}

	public static YReferentiInseriti getReferenteInseritoByKey(String key) {
		try {
			return (YReferentiInseriti) YReferentiInseriti.elementWithKey(YReferentiInseriti.class,key, PersistentObject.NO_LOCK);
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return null;
	}

	public String getJsonReferenteDaRubrica(RubricaEstesa obj,String idAccount) {
		JsonObject json = new JsonObject();
		json.addProperty("AccountId", idAccount);
		if(obj.getCognome() != null)
			json.addProperty("LastName", obj.getCognome());
		if(obj.getNome() != null)
			json.addProperty("FirstName", obj.getNome());
		json.addProperty("MailingStreet", obj.getRubIndirizzo() != null ? obj.getRubIndirizzo() : "");
		json.addProperty("MailingCity", obj.getLocalita() != null ? obj.getLocalita() : "");
		json.addProperty("MailingState", obj.getIdProvincia() != null ? obj.getIdProvincia() : "");
		json.addProperty("MailingPostalCode", obj.getCAP() != null ? obj.getCAP() : "");
		json.addProperty("MailingCountry", obj.getAnagraficoDiBase().getIdNazioneNascita() != null ? obj.getAnagraficoDiBase().getIdNazioneNascita() : "");
		json.addProperty("OtherStreet", obj.getRubIndirizzo() != null ? obj.getRubIndirizzo() : "");
		json.addProperty("OtherCity", obj.getLocalita() != null ? obj.getLocalita() : "");
		json.addProperty("OtherState", obj.getIdProvincia() != null ? obj.getIdProvincia() : "");
		json.addProperty("OtherPostalCode", obj.getCAP() != null ? obj.getCAP() : "");
		json.addProperty("OtherCountry", obj.getAnagraficoDiBase().getIdNazioneNascita() != null ? obj.getAnagraficoDiBase().getIdNazioneNascita() : "");
		json.addProperty("Phone", obj.getNumeroTelefono() != null ? obj.getNumeroTelefono() : "");
		json.addProperty("Fax", obj.getNumeroFax() != null ? obj.getNumeroFax() : "");
		json.addProperty("MobilePhone", obj.getNumeroCellulare() != null ? obj.getNumeroCellulare() : "");
		json.addProperty("HomePhone", obj.getIndirizzoPersonale().getTelefono() != null ? obj.getIndirizzoPersonale().getTelefono() : "");
		json.addProperty("Email",obj.getIndirizzoEmail() != null ? obj.getIndirizzoEmail() : "");
		if(obj.getDataNascita() != null)
			json.addProperty("Birthdate",obj.getDataNascita().toString());
		json.addProperty("Title",obj.getFunzioneAziendale() != null ? obj.getFunzioneAziendale() : "");
		return json.toString();
	}

	public static YClientiInseriti getClienteInseritoByKey(String key) {
		try {
			return (YClientiInseriti) YClientiInseriti.elementWithKey(YClientiInseriti.class,key, PersistentObject.NO_LOCK);
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return null;
	}

	public String getJSONAdd(ClienteVendita obj) {
		JsonObject json = new JsonObject();
		json.addProperty("Name", obj.getRagioneSociale());
		json.addProperty("BillingStreet", obj.getIndirizzo() != null ? obj.getIndirizzo() : "");
		json.addProperty("BillingCity",obj.getLocalita() != null ? obj.getLocalita() : "");
		json.addProperty("BillingState",obj.getIdProvincia() != null ? obj.getIdProvincia() : "");
		json.addProperty("BillingPostalCode",obj.getCAP() != null ? obj.getCAP() : "");
		json.addProperty("BillingCountry",obj.getIdNazione() != null ? obj.getIdNazione() : "");
		json.addProperty("ShippingStreet", obj.getIndirizzo() != null ? obj.getIndirizzo() : "");
		json.addProperty("ShippingCity",obj.getLocalita() != null ? obj.getLocalita() : "");
		json.addProperty("ShippingState",obj.getIdProvincia() != null ? obj.getIdProvincia() : "");
		json.addProperty("ShippingPostalCode",obj.getCAP() != null ? obj.getCAP() : "");
		json.addProperty("ShippingCountry",obj.getIdNazione() != null ? obj.getIdNazione() : "");
		json.addProperty("Phone",obj.getAnagrafico().getNumeroTelefono() != null ? obj.getAnagrafico().getNumeroTelefono() : "");
		json.addProperty("Fax",obj.getAnagrafico().getNumeroFax() != null ? obj.getAnagrafico().getNumeroFax() : "");
		json.addProperty("Website",obj.getAnagrafico().getSitoInternet() != null ? obj.getAnagrafico().getSitoInternet() : "");
		json.addProperty("VAT_Number__c", obj.getPartitaIVA());
		return json.toString();
	}

	@SuppressWarnings("unchecked")
	public List<ClienteVendita> getListaClientiValidi(){
		List<ClienteVendita> lista = new ArrayList<ClienteVendita>();
		try {
			String where = " "+ClienteVenditaTM.ID_AZIENDA+" = '"+Azienda.getAziendaCorrente()+"' AND "+ClienteVenditaTM.STATO+" = '"+DatiComuniEstesi.VALIDO+"' ";
			lista = ClienteVendita.retrieveList(ClienteVendita.class,where, "", false);
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return lista;
	}

	protected void writeLog(String text) {
		System.out.println(text);
		getOutput().println(text);
	}

	@Override
	protected String getClassAdCollectionName() {
		return "YClientiSF";
	}

}
