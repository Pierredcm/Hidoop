package hdfs;

import java.rmi.Naming;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.Properties;

import config.InvalidPropertyException;
import config.Project;
import formats.Format;
import ordo.NameNodeInterface;

import java.util.List;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;

public class HdfsClient {

	// private static final long serialVersionUID = 1L;
	/** Structure contient toutes la configuration initiale du projet ainsi que les 
	 * informations qu'on a � donner � Hidoop.
	 */
	private static Project structure = new Project();
	
	/** L'URL de la machine contenant le namenode. */
	private static String nameNodeURL;

	/** Nombre de daemons hidoop disponible */
	private static int nbNodes;

	/** D�finie la taille maximal d'un fragment lors de l'�criture. */
	private static int tailleMaxFragment = 100;

	/** Port vers les ndoes */
	private static int port = 8000;
	
	/** Texte recu lors d'une lecture. */
	private static String strRecu;

	private static void usage() {
		System.out.println("Usage: java HdfsClient read <file>");
		System.out.println("Usage: java HdfsClient write <line|kv> <file>");
		System.out.println("Usage: java HdfsClient delete <file>");
	}

	public static void HdfsDelete(String hdfsFname) {

		// Voir quel node possede le fichier (consultation du namenode)
		// pour utiliser la bonne connexion.
		HashMap<Integer, String> mappingBlocs = structure.daemonsFragmentRepartized.get(hdfsFname);
		int node = 0;
		String fSansExtension = hdfsFname.replaceFirst("[.][^.]+$", "");
				
		mappingBlocs.forEach((i, url) -> {
			Socket sock;
			try {
				sock = new Socket(url, port);
				Connexion c = new Connexion(sock);
				Commande cmd = new Commande(Commande.Cmd.CMD_DELETE, fSansExtension + "-bloc" + i, 0);
				c.send(cmd);
				c.Close();
				// On supprime de la structure du projet le fragment.
				structure.daemonsFragmentRepartized.get(hdfsFname).remove(i);
			} catch (UnknownHostException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
	}

	/***************** WRITE ********************/
	public static void HdfsWrite(Format.Type fmt, String localFSSourceFname, int repFactor) {

		File f = new File(Project.PATH + localFSSourceFname);
		/* On récupère le nom du fichier sans l'extension (.txt en general) */
		String fSansExtension = localFSSourceFname.replaceFirst("[.][^.]+$", "");
		long tailleFichier = f.length();

		Commande cmd = new Commande(Commande.Cmd.CMD_WRITE, "", 0);

		/* Buffer d'envoi de texte */
		char[] buf = new char[tailleMaxFragment];

		int nbFragment = (int) (tailleFichier / tailleMaxFragment);
		int reste = (int) (tailleFichier % tailleMaxFragment);
		System.out.println("Nombre de fragments requis : " + nbFragment);
		System.out.println("Caractère restant : " + reste);

		/* On aura besoin d'un fragment de plus s'il y a un reste à la divison */
		if (reste != 0) {
			System.out.println("Reste différent de 0 donc ajout d'un fragment.");
			nbFragment++;
		}
		/*
		 * Ce buffer servira à transmettre le dernier fragments qui n'aura pas un
		 * nombre fixe de caractere.
		 */
		char[] miniBuf = new char[reste];

		try {
			/*
			 * On va utiliser un file reader/writer sur les fichiers pour pouvoir lire leur
			 * contenu et le mettre dans un buffer avant de l'envoyer.
			 */
			FileReader fr = new FileReader(f);
			/* Donne le numéro de node sur lequel on écrit un fragment. */
			int node = 0;
			int i;
			/* Liste contenant tous les noms de fragments */
			HashMap<Integer, String> mappingBlocs = new HashMap<Integer, String>();
			
			for (i = 0; i < nbFragment; i++) {
				Socket sock = new Socket("Localhost", port);
				Connexion c = new Connexion(sock);
				System.out.println("Ecriture du fragment n " + i + " sur le node " + node);
				System.out.println("Lecture...");

				int ret = fr.read(buf, 0, tailleMaxFragment);

				if (ret != tailleMaxFragment) {
					System.out.println("Fin du fichier atteinte, ecriture du dernier fragment");
					System.out.println("Envoi de la commande d'ecriture...");

					cmd = new Commande(Commande.Cmd.CMD_WRITE, fSansExtension + "-bloc" + i, reste);
					c.send(cmd);
					/*
					 * On copie la dernière lecture dans le buffer plus petit ayant une taille
					 * adapté au nombres de caractère restants.
					 */
					System.arraycopy(buf, 0, miniBuf, 0, reste);
					System.out.println("Envoi du fragment...");
					c.send(miniBuf);
				} else {
					System.out.println("Envoi de la commande d'ecriture...");
					/* Envoi de la commande */
					cmd = new Commande(Commande.Cmd.CMD_WRITE, fSansExtension + "-bloc" + i, tailleMaxFragment);
					c.send(cmd);
					System.out.println("Envoi du fragment...");
					c.send(buf);
				}
				
				/* Association du fragment au node correspondant */
				mappingBlocs.put(i, structure.urlNodes.get(node));
				
				node++;
				if (node == nbNodes) {
					node = 0;
				}
				c.Close();
			}
			System.out.println("Fin de l'ecriture " + i + " fragment ecrits.");
			
			structure.daemonsFragmentRepartized.put(localFSSourceFname, mappingBlocs);
			
			fr.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static void HdfsRead(String hdfsFname, String localFSDestFname) {
		// Voir quel node possede le fichier (consultation du namenode)
		// pour utiliser la bonne connexion.
		HashMap<Integer, String> mappingBlocs = structure.daemonsFragmentRepartized.get(hdfsFname);
		String fSansExtension = hdfsFname.replaceFirst("[.][^.]+$", "");
		int node = 0;

		/* Fichier dans lequel on �crira le r�sultat de la lecture */
		File f = new File(localFSDestFname);
		FileWriter fw;

		try {
			strRecu = new String();
			
			fw = new FileWriter(f);
			
			/* Pour chaque fragment, on poss�de l'URL du node le stockant. */
			mappingBlocs.forEach((i, url) -> {
				Socket sock;
				try {
				/* On ouvre une connexion poura chaque fragment, on lie, on le concatene � strRecu */
					sock = new Socket(url, port);
					Connexion c = new Connexion(sock);
					
				/* Rappel : le nom d'un fragment est nom_du_fichier-blocx avec x num�ro du fragment. */
					Commande cmd = new Commande(Commande.Cmd.CMD_READ, fSansExtension + "-bloc" + i, 0);
					c.send(cmd);
					
					// On concatene les textes de tous les fragments.
					strRecu = strRecu + (String) c.receive();
	
					c.Close();
				} catch (UnknownHostException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			});
			fw.write(strRecu);
			fw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}



	/*
	 * public Project getStructureMapReduce() { return structure; }
	 */
	/* public static HashMap<Integer, HashMap<Integer, String>> repartition_des_blocs(
			HashMap<Integer, String> listOfDaemons) {

		HashMap<Integer, String> l = new HashMap<Integer, String>();
		HashMap<Integer, HashMap<Integer, String>> repartition_bloc = new HashMap<Integer, HashMap<Integer, String>>();

		// Fichier 1
		l.put(1, listOfDaemons.get(1));
		repartition_bloc.put(1, l);

		/*
		 * l.put(2, listOfDaemons.get(2)); l.put(3, listOfDaemons.get(3)); l.put(4,
		 * listOfDaemons.get(4)); l.put(5, listOfDaemons.get(5)); l.put(6,
		 * listOfDaemons.get(6));
		 * 
		 * 
		 * // fichier 2 l = new HashMap<Integer, String>(); l.put(1,
		 * listOfDaemons.get(3)); l.put(2, listOfDaemons.get(4));
		 * 
		 * repartition_bloc.put(2, l);
		 * 
		 * // fichier 3 l = new HashMap<Integer, String>(); l.put(1,
		 * listOfDaemons.get(5)); l.put(2, listOfDaemons.get(6));
		 * 
		 * repartition_bloc.put(3, l);
		

		return repartition_bloc;

	} */

	public static String getNamNodeURL() {
		String res = null;
		try {
			FileInputStream in = new FileInputStream("hidoop/data/hdfsClient/NameNode.url");
			Properties prop = new Properties();
			prop.load(in);
			in.close();

			res = prop.getProperty("url");
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(0);
		}
		return res;

	}

	public static void main(String[] args) {

		structure = new Project();
		
		nbNodes = structure.urlNodes.size();
		
		try {
			NameNodeInterface nNI;

			nNI = (NameNodeInterface) Naming.lookup(getNamNodeURL());

			if (args.length < 2) {
				usage();
				return;
			}

			switch (args[0]) {
			case "read":
				HdfsRead(args[1], null);
				break;
			case "delete":
				HdfsDelete(args[1]);
				break;
			case "write":

				Format.Type fmt;
				if (args.length < 3) {
					usage();
					return;
				}
				if (args[1].equals("line"))
					fmt = Format.Type.LINE;
				else if (args[1].equals("kv"))
					fmt = Format.Type.KV;
				else {
					usage();
					return;
				}
				HdfsWrite(fmt, args[2], 1);
			}

			/* Mise � jour du namenode */
			nNI.updateStructure(structure);
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}
	}

}
