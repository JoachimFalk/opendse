/*******************************************************************************
 * Copyright (c) 2015 OpenDSE
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *******************************************************************************/
package net.sf.opendse.optimization.encoding;

import static edu.uci.ics.jung.graph.util.EdgeType.UNDIRECTED;
import static net.sf.opendse.model.Models.filterCommunications;
import static net.sf.opendse.model.Models.filterProcesses;
import static net.sf.opendse.model.Models.getInLinks;
import static net.sf.opendse.model.Models.getLinks;
import static net.sf.opendse.model.Models.getOutLinks;
import static net.sf.opendse.model.Models.isProcess;
import static net.sf.opendse.optimization.encoding.variables.Variables.p;
import static net.sf.opendse.optimization.encoding.variables.Variables.var;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import net.sf.opendse.model.Application;
import net.sf.opendse.model.Architecture;
import net.sf.opendse.model.Dependency;
import net.sf.opendse.model.Edge;
import net.sf.opendse.model.Link;
import net.sf.opendse.model.Mapping;
import net.sf.opendse.model.Mappings;
import net.sf.opendse.model.Resource;
import net.sf.opendse.model.Routings;
import net.sf.opendse.model.Specification;
import net.sf.opendse.model.Task;
import net.sf.opendse.model.Models.DirectedLink;
import net.sf.opendse.optimization.constraints.SpecificationConstraints;
import net.sf.opendse.optimization.encoding.variables.CLRR;
import net.sf.opendse.optimization.encoding.variables.CR;

import org.opt4j.satdecoding.Constraint;

import com.google.inject.Inject;

import edu.uci.ics.jung.graph.util.Pair;

/**
 * The {@code Encoding} transforms the exploration problem into a set of
 * constraints.
 * 
 * @author Martin Lukasiewycz
 * 
 */
public class Encoding {

	public static List<Class<?>> order = Arrays.<Class<?>> asList(Resource.class, Link.class, Mapping.class, CR.class, CLRR.class);

	public enum RoutingEncoding {
		HOP, FLOW;
	}
	
	public static class VariableComparator implements Comparator<Object>, Serializable {

		private static final long serialVersionUID = 1L;

		protected Integer order(Object obj) {
			int i = 0;
			for (Class<?> clazz : order) {
				if (clazz.isAssignableFrom(obj.getClass())) {
					return i;
				}
				i++;
			}
			return 100;
		}

		@Override
		public int compare(Object o0, Object o1) {
			return order(o0).compareTo(order(o1));
		}

	}

	protected final SpecificationConstraints specificationConstraints;
	protected final RoutingEncoding routingEncoding;

	@Inject
	public Encoding(SpecificationConstraints specificationConstraints, RoutingEncoding routingEncoding) {
		super();
		this.specificationConstraints = specificationConstraints;
		this.routingEncoding = routingEncoding;
	}

	protected void EQ1(List<Constraint> constraints, Specification specification) {
		for (Task task : filterProcesses(specification.getApplication())) {
			Constraint constraint = new Constraint("=", 1);
			for (Mapping<Task, Resource> m : specification.getMappings().get(task)) {
				constraint.add(p(m));
			}
			constraints.add(constraint);
		}
	}

	protected void EQ2(List<Constraint> constraints, Specification specification) {
		for (Mapping<Task, Resource> m : specification.getMappings()) {
			Constraint constraint = new Constraint(">=", 0);
			Resource r = m.getTarget();
			constraint.add(p(r));
			constraint.add(-1, p(m));
			constraints.add(constraint);
		}
	}

	protected void EQ3EQ4(List<Constraint> constraints, Specification specification) {
		for (Dependency dependency : specification.getApplication().getEdges()) {
			Task p0 = specification.getApplication().getSource(dependency);
			Task p1 = specification.getApplication().getDest(dependency);

			if (isProcess(p0) && isProcess(p1)) {
				for (Mapping<Task, Resource> m0 : specification.getMappings().get(p0)) {
					for (Mapping<Task, Resource> m1 : specification.getMappings().get(p1)) {
						Resource r0 = m0.getTarget();
						Resource r1 = m1.getTarget();

						Edge l = specification.getArchitecture().findEdge(r0, r1);

						if (l != null) {
							Constraint constraint = new Constraint(">=", -1); // EQ3
							constraint.add(p(l));
							constraint.add(-1, p(m0));
							constraint.add(-1, p(m1));
							constraints.add(constraint);
						} else if (!r0.equals(r1)) {
							Constraint constraint = new Constraint("<=", 1); // EQ4
							constraint.add(p(m0));
							constraint.add(p(m1));
							constraints.add(constraint);
						}
					}
				}
			}
		}
	}

	protected void EQ5(List<Constraint> constraints, Specification specification) {
		for (Link l : specification.getArchitecture().getEdges()) {
			Pair<Resource> pair = specification.getArchitecture().getEndpoints(l);
			Resource r0 = pair.getFirst();
			Resource r1 = pair.getSecond();

			Constraint constraint = new Constraint(">=", 0);
			constraint.add(-2, p(l));
			constraint.add(p(r0));
			constraint.add(p(r1));
			constraints.add(constraint);
		}
	}

	protected void EQ6(List<Constraint> constraints, Specification specification) {
		for (Task c : filterCommunications(specification.getApplication())) {
			Architecture<Resource, Link> routing = specification.getRoutings().get(c);
			for (Resource r : routing) {
				Constraint constraint = new Constraint(">=", 0);
				constraint.add(p(r));
				constraint.add(-1, p(var(c, r)));
				constraints.add(constraint);
			}
		}
	}

	protected void EQ7(List<Constraint> constraints, Specification specification) {
		for (Task c : filterCommunications(specification.getApplication())) {
			Architecture<Resource, Link> routing = specification.getRoutings().get(c);
			for (DirectedLink lrr : getLinks(routing)) {
				Constraint constraint = new Constraint(">=", 0);
				constraint.add(p(lrr.getLink()));
				constraint.add(-1, p(var(c, lrr)));
				constraints.add(constraint);
			}
		}
	}

	protected void EQ8(List<Constraint> constraints, Specification specification) {
		for (Task c : filterCommunications(specification.getApplication())) {
			Architecture<Resource, Link> routing = specification.getRoutings().get(c);
			for (DirectedLink lrr : getLinks(routing)) {
				Resource r0 = lrr.getSource();
				Resource r1 = lrr.getDest();

				Constraint constraint = new Constraint(">=", 0);
				constraint.add(-2, p(var(c, lrr)));
				constraint.add(p(var(c, r0)));
				constraint.add(p(var(c, r1)));
				constraints.add(constraint);
			}
		}
	}

	protected void EQ9(List<Constraint> constraints, Specification specification) {
		for (Task c : filterCommunications(specification.getApplication())) {
			Architecture<Resource, Link> routing = specification.getRoutings().get(c);

			for (Link l : routing.getEdges()) {
				if (routing.getEdgeType(l) == UNDIRECTED) {
					Pair<Resource> endpoints = routing.getEndpoints(l);
					Resource r0 = endpoints.getFirst();
					Resource r1 = endpoints.getSecond();

					Constraint constraint = new Constraint("<=", 1);
					constraint.add(p(var(c, l, r0, r1)));
					constraint.add(p(var(c, l, r1, r0)));
					constraints.add(constraint);
				}
			}
		}
	}

	protected void EQ10EQ11(List<Constraint> constraints, Specification specification) {
		for (Task c : filterCommunications(specification.getApplication())) {
			for (Task p : filterProcesses(specification.getApplication().getNeighbors(c))) {
				for (Mapping<Task, Resource> m : specification.getMappings().get(p)) {
					Resource r = m.getTarget();

					if (specification.getRoutings().get(c).containsVertex(r)) {
						Constraint constraint = new Constraint(">=", 0); // EQ10
						constraint.add(p(var(c, r)));
						constraint.add(-1, p(m));
						constraints.add(constraint);
					} else {
						Constraint constraint = new Constraint("=", 0); // EQ11
						constraint.add(p(m));
						constraints.add(constraint);
					}
				}
			}
		}
	}

	protected void EQ12(List<Constraint> constraints, Specification specification) {
		for (Task c : filterCommunications(specification.getApplication())) {
			for (Task p : filterProcesses(specification.getApplication().getPredecessors(c))) {
				for (Mapping<Task, Resource> m : specification.getMappings().get(p)) {
					Resource r0 = m.getTarget();
					Architecture<Resource, Link> routing = specification.getRoutings().get(c);

					for (DirectedLink lrr : getInLinks(routing, r0)) {
						Constraint constraint = new Constraint("<=", 1);
						constraint.add(p(m));
						constraint.add(p(var(c, lrr)));
						constraints.add(constraint);
					}

				}
			}
		}
	}

	protected void EQ13(List<Constraint> constraints, Specification specification) {
		for (Task c : filterCommunications(specification.getApplication())) {
			Architecture<Resource, Link> routing = specification.getRoutings().get(c);

			for (Resource r0 : routing) {
				Constraint constraint = new Constraint("<=", 1);
				for (DirectedLink lrr : getInLinks(routing, r0)) {
					constraint.add(p(var(c, lrr)));
				}
				constraints.add(constraint);
			}
		}
	}

	protected void EQ14(List<Constraint> constraints, Specification specification) {
		for (Task c : filterCommunications(specification.getApplication())) {
			Architecture<Resource, Link> routing = specification.getRoutings().get(c);

			for (Resource r0 : routing) {
				Constraint constraint = new Constraint(">=", 0);
				constraint.add(-1, p(var(c, r0)));

				for (Task p : filterProcesses(specification.getApplication().getSuccessors(c))) {
					for (Mapping<Task, Resource> m : specification.getMappings().get(p, r0)) {
						constraint.add(p(m));
					}
				}
				for (DirectedLink lrr : getOutLinks(routing, r0)) {
					constraint.add(p(var(c, lrr)));
				}
				constraints.add(constraint);
			}
		}
	}

	protected void EQ15(List<Constraint> constraints, Specification specification) {
		for (Task c : filterCommunications(specification.getApplication())) {
			Architecture<Resource, Link> routing = specification.getRoutings().get(c);

			for (Resource r0 : routing) {
				Constraint constraint = new Constraint(">=", 0);
				constraint.add(-1, p(var(c, r0)));

				for (Task p : filterProcesses(specification.getApplication().getPredecessors(c))) {
					for (Mapping<Task, Resource> m : specification.getMappings().get(p, r0)) {
						constraint.add(p(m));
					}
				}
				for (DirectedLink lrr : getInLinks(routing, r0)) {
					constraint.add(p(var(c, lrr)));
				}

				constraints.add(constraint);
			}
		}
	}

	protected void EQ16(List<Constraint> constraints, Specification specification) {
		for (Task c : filterCommunications(specification.getApplication())) {
			Architecture<Resource, Link> routing = specification.getRoutings().get(c);

			for (Resource r0 : routing) {
				Constraint constraint = new Constraint("<=", 1);
				for (DirectedLink lrr : getOutLinks(routing, r0)) {
					constraint.add(p(var(c, lrr)));
				}
				constraints.add(constraint);
			}
		}
	}

	protected void EQ17(List<Constraint> constraints, Specification specification) {
		final Application<Task, Dependency> application = specification.getApplication();

		for (Task c : filterCommunications(application)) {
			assert (application.getPredecessorCount(c) == 1);
			assert (application.getSuccessorCount(c) == 1);

			Task p0 = application.getPredecessors(c).iterator().next();
			Task p1 = application.getSuccessors(c).iterator().next();

			Architecture<Resource, Link> routing = specification.getRoutings().get(c);

			for (Resource r0 : routing) {
				Constraint constraint = new Constraint("=", 0);

				for (DirectedLink lrr : getOutLinks(routing, r0)) {
					constraint.add(1, p(var(c, lrr)));
				}
				for (DirectedLink lrr : getInLinks(routing, r0)) {
					constraint.add(-1, p(var(c, lrr)));
				}
				for (Mapping<Task, Resource> m : specification.getMappings().get(p0, r0)) {
					constraint.add(-1, p(m));
				}
				for (Mapping<Task, Resource> m : specification.getMappings().get(p1, r0)) {
					constraint.add(1, p(m));
				}
				constraints.add(constraint);
			}
		}
	}

	protected void EQ18(List<Constraint> constraints, Specification specification) {
		final Application<Task, Dependency> application = specification.getApplication();

		for (Task c : filterCommunications(application)) {
			for (Task p0 : filterProcesses(application.getPredecessors(c))) {
				for (Task p1 : filterProcesses(application.getSuccessors(c))) {
					for (Mapping<Task, Resource> m : specification.getMappings().get(p0)) {
						Resource r0 = m.getTarget();
						Architecture<Resource, Link> routing = specification.getRoutings().get(c);

						for (DirectedLink lrr : getInLinks(routing, r0)) {
							Constraint constraint = new Constraint("<=", 1);
							constraint.add(p(m));
							constraint.add(p(var(c, lrr, p1)));
							constraints.add(constraint);
						}
					}
				}
			}
		}
	}

	protected void EQ19(List<Constraint> constraints, Specification specification) {
		final Application<Task, Dependency> application = specification.getApplication();

		for (Task c : filterCommunications(application)) {
			for (Task p : filterProcesses(application.getSuccessors(c))) {
				Architecture<Resource, Link> routing = specification.getRoutings().get(c);

				for (Resource r0 : routing) {
					Constraint constraint = new Constraint("<=", 1);
					for (DirectedLink lrr : getInLinks(routing, r0)) {
						constraint.add(p(var(c, lrr, p)));
					}
					constraints.add(constraint);
				}
			}
		}
	}

	protected void EQ20(List<Constraint> constraints, Specification specification) {
		final Application<Task, Dependency> application = specification.getApplication();

		for (Task c : filterCommunications(application)) {
			for (Task p : filterProcesses(application.getSuccessors(c))) {
				Architecture<Resource, Link> routing = specification.getRoutings().get(c);

				for (Resource r0 : routing) {
					Constraint constraint = new Constraint("<=", 1);
					for (Link l : routing.getOutEdges(r0)) {
						Resource r1 = routing.getOpposite(r0, l);
						constraint.add(p(var(c, l, r0, r1, p)));
					}
					constraints.add(constraint);
				}
			}
		}
	}

	protected void EQ21(List<Constraint> constraints, Specification specification) {
		final Application<Task, Dependency> application = specification.getApplication();

		for (Task c : filterCommunications(application)) {
			assert (application.getPredecessorCount(c) == 1);
			Task p0 = application.getPredecessors(c).iterator().next();
			for (Task p1 : filterProcesses(application.getSuccessors(c))) {
				Architecture<Resource, Link> routing = specification.getRoutings().get(c);

				for (Resource r0 : routing) {
					Constraint constraint = new Constraint("=", 0);

					for (DirectedLink lrr : getOutLinks(routing, r0)) {
						constraint.add(1, p(var(c, lrr, p1)));
					}
					for (DirectedLink lrr : getInLinks(routing, r0)) {
						constraint.add(-1, p(var(c, lrr, p1)));
					}
					for (Mapping<Task, Resource> m : specification.getMappings().get(p0, r0)) {
						constraint.add(-1, p(m));
					}
					for (Mapping<Task, Resource> m : specification.getMappings().get(p1, r0)) {
						constraint.add(1, p(m));
					}
					constraints.add(constraint);
				}
			}
		}
	}

	protected void EQ22(List<Constraint> constraints, Specification specification) {
		final Application<Task, Dependency> application = specification.getApplication();
		for (Task c : filterCommunications(application)) {
			Architecture<Resource, Link> routing = specification.getRoutings().get(c);
			for (DirectedLink lrr : getLinks(routing)) {
				Constraint constraint = new Constraint(">=", 0);
				constraint.add(-1, p(var(c, lrr)));
				for (Task p : filterProcesses(application.getSuccessors(c))) {
					constraint.add(p(var(c, lrr, p)));
				}
				constraints.add(constraint);
			}
		}
	}

	protected void EQ23(List<Constraint> constraints, Specification specification) {
		final Application<Task, Dependency> application = specification.getApplication();
		for (Task c : filterCommunications(application)) {
			for (Task p : filterProcesses(application.getSuccessors(c))) {
				Architecture<Resource, Link> routing = specification.getRoutings().get(c);
				for (DirectedLink lrr : getLinks(routing)) {
					Constraint constraint = new Constraint(">=", 0);
					constraint.add(-1, p(var(c, lrr, p)));
					constraint.add(p(var(c, lrr)));
					constraints.add(constraint);
				}
			}
		}
	}

	protected void EQ30(List<Constraint> constraints, Specification specification) {
		final Application<Task, Dependency> application = specification.getApplication();
		final Architecture<Resource, Link> architecture = specification.getArchitecture();
		final Mappings<Task, Resource> mappings = specification.getMappings();
		final Routings<Task, Resource, Link> routings = specification.getRoutings();

		// EQ30 (redundant - resource constraints)
		for (Resource r : architecture) {
			Constraint constraint = new Constraint(">=", 0);
			constraint.add(-1, p(r));
			for (Mapping<Task, Resource> m : mappings.get(r)) {
				constraint.add(p(m));
			}
			for (Task c : filterCommunications(application)) {
				Architecture<Resource, Link> routing = routings.get(c);
				if (routing.containsVertex(r)) {
					constraint.add(p(var(c, r)));
				}
			}
			constraints.add(constraint);
		}
	}

	public List<Constraint> toConstraints(Specification specification) {
		List<Constraint> constraints = new ArrayList<Constraint>();

		Application<Task, Dependency> application = specification.getApplication();
		Architecture<Resource, Link> architecture = specification.getArchitecture();
		Mappings<Task, Resource> mappings = specification.getMappings();
		Routings<Task, Resource, Link> routings = specification.getRoutings();

		EQ1(constraints, specification);
		EQ2(constraints, specification);
		EQ3EQ4(constraints, specification);
		EQ5(constraints, specification);
		EQ6(constraints, specification);

		EQ7(constraints, specification);
		EQ8(constraints, specification);
		EQ9(constraints, specification);
		EQ10EQ11(constraints, specification);
		EQ12(constraints, specification);
		EQ13(constraints, specification);
		EQ14(constraints, specification);
		EQ15(constraints, specification);

		boolean isUnicast = false;
		boolean isMulticast1 = true;
		boolean isMulticast2 = false;

		final int Tmax = 10;

		if (isUnicast) {
			EQ16(constraints, specification);
			EQ17(constraints, specification);
		}
		if (routingEncoding.equals(RoutingEncoding.FLOW)) {
			EQ18(constraints, specification);
			EQ19(constraints, specification);
			EQ20(constraints, specification);
			EQ21(constraints, specification);
			EQ22(constraints, specification);
			EQ23(constraints, specification);
		}

		if (routingEncoding.equals(RoutingEncoding.HOP)) {

			// EQ24
			for (Task c : filterCommunications(application)) {
				Task p = application.getPredecessors(c).iterator().next();
				Architecture<Resource, Link> routing = routings.get(c);

				for (DirectedLink lrr : getLinks(routing)) {
					Constraint constraint = new Constraint(">=", 0);
					constraint.add(-1, p(var(c, lrr, 1)));
					Resource r0 = lrr.getSource();
					for (Mapping<Task, Resource> m : mappings.get(p, r0)) {
						constraint.add(p(m));
					}
					constraints.add(constraint);
				}
			}

			// EQ25 TODO (redundant)
			for (Task c : filterCommunications(application)) {
				Task p = application.getPredecessors(c).iterator().next();
				Architecture<Resource, Link> routing = routings.get(c);

				List<Resource> rs = new ArrayList<Resource>(mappings.getTargets(p));

				for (int i = 0; i < rs.size(); i++) {
					for (int j = i + 1; j < rs.size(); j++) {
						Resource r0 = rs.get(i);
						Resource r1 = rs.get(j);

						for (DirectedLink lrr0 : getOutLinks(routing, r0)) {
							for (DirectedLink lrr1 : getOutLinks(routing, r1)) {
								Constraint constraint = new Constraint("<=", 1);
								constraint.add(p(var(c, lrr0, 1)));
								constraint.add(p(var(c, lrr1, 1)));
								constraints.add(constraint);
							}
						}
					}
				}
			}

			// EQ26
			for (Task c : filterCommunications(application)) {
				Architecture<Resource, Link> routing = routings.get(c);

				for (DirectedLink lrr0 : getLinks(routing)) {
					for (int t = 2; t <= Tmax; t++) {

						Constraint constraint = new Constraint(">=", 0);
						constraint.add(-1, p(var(c, lrr0, t)));
						for (DirectedLink lrr1 : getInLinks(routing, lrr0.getSource())) {
							constraint.add(p(var(c, lrr1, t - 1)));
						}
						constraints.add(constraint);
					}
				}
			}

			// EQ27
			for (Task c : filterCommunications(application)) {
				Architecture<Resource, Link> routing = routings.get(c);

				for (DirectedLink lrr : getLinks(routing)) {
					Constraint constraint = new Constraint("<=", 1);
					for (int t = 1; t <= Tmax; t++) {
						constraint.add(p(var(c, lrr, t)));
					}
					constraints.add(constraint);
				}
			}

			// EQ28
			for (Task c : filterCommunications(application)) {
				Architecture<Resource, Link> routing = routings.get(c);

				for (DirectedLink lrr : getLinks(routing)) {
					Constraint constraint = new Constraint(">=", 0);
					constraint.add(-1, p(var(c, lrr)));

					for (int t = 1; t <= Tmax; t++) {
						constraint.add(p(var(c, lrr, t)));
					}
					constraints.add(constraint);
				}
			}

			// EQ29
			for (Task c : filterCommunications(application)) {
				Architecture<Resource, Link> routing = routings.get(c);

				for (DirectedLink lrr : getLinks(routing)) {
					for (int t = 1; t <= Tmax; t++) {
						Constraint constraint = new Constraint(">=", 0);
						constraint.add(-1, p(var(c, lrr, t)));
						constraint.add(p(var(c, lrr)));
						constraints.add(constraint);
					}
				}
			}
		}
		
		EQ30(constraints, specification);

		specificationConstraints.doEncoding(constraints);

		return constraints;
	}

}